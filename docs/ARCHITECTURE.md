# LogStream Architecture

This document describes the runtime architecture of LogStream: its components,
their responsibilities, and the end-to-end flows for ingestion, search, live tail
and alerting.

---

## 1. Component overview

| Component | Class(es) | Responsibility |
|-----------|-----------|----------------|
| **gRPC Ingestion Server** | [`GrpcServerConfig`](../logstream-main/backend/logstream-backend/src/main/java/logstream_backend/config/GrpcServerConfig.java) | Binds port 9090, registers [`LogServiceImpl`](../logstream-main/backend/logstream-backend/src/main/java/logstream_backend/service/LogServiceImpl.java) |
| **Ingestion Interceptor** | `TenantServerInterceptor` | Extracts `tenant-id` from gRPC metadata and binds it to the request thread |
| **Log Service** | [`LogServiceImpl`](../logstream-main/backend/logstream-backend/src/main/java/logstream_backend/service/LogServiceImpl.java) | Validates + hands off log batches, broadcasts to Live Tail |
| **Index Manager** | `LuceneIndexManager` | Owns one long-lived `IndexWriter` per tenant; manages NRT readers and commits. **The performance-critical component.** |
| **Search Service** | [`LuceneLogService`](../logstream-main/backend/logstream-backend/src/main/java/logstream_backend/service/LuceneLogService.java) | Document CRUD, query execution, facets, histogram aggregation |
| **Query Parser** | `LogQueryParser` | Parses `field:value AND range` lucene-style expressions with tenant scoping |
| **Analytics Service** | `LogAnalyticsService` | Aggregations (per-minute counts, level/service distributions) |
| **Metadata Store** | JPA entities + repos | `tenants`, `log_metadata`, `alert_rules`, `alert_events` |
| **Alert Scheduler** | [`AlertScheduler`](../logstream-main/backend/logstream-backend/src/main/java/logstream_backend/service/AlertScheduler.java) | `@Scheduled` rule evaluation every minute |
| **Alert Service** | [`AlertService`](../logstream-main/backend/logstream-backend/src/main/java/logstream_backend/service/AlertService.java) | Evaluates rules, dedupes incidents, dispatches actions |
| **Notifiers** | `WebhookNotifier`, [`EmailService`](../logstream-main/backend/logstream-backend/src/main/java/logstream_backend/service/EmailService.java) | Deliver alert payloads |
| **REST Controllers** | `LogController`, `SearchController`, `AnalyticsController`, `AlertController` | HTTP API |
| **Live Tail** | [`LogWebSocketHandler`](../logstream-main/backend/logstream-backend/src/main/java/logstream_backend/websocket/LogWebSocketHandler.java) | Fan-out of raw logs to browsers, bypassing the index |

---

## 2. Component diagram

```
┌───────────────────────────── Client side ─────────────────────────────┐
│  React 19 + Vite + ECharts                                             │
│  ├─ Overview      (stats + volume chart)                              │
│  ├─ Logs          (query bar + result table + histogram)               │
│  ├─ Live Tail     (WebSocket stream)                                   │
│  ├─ Analytics     (histogram / level / service charts)                 │
│  ├─ Alerts        (rule CRUD + active incidents)                       │
│  └─ Settings      (preferences, connection status)                     │
└───────────┬───────────────────────────────┬───────────────────────────┘
            │ REST + X-Tenant-Id            │ WebSocket
            ▼                                ▼
┌───────────────────────────── Spring Boot ──────────────────────────────┐
│  TenantContextFilter → Controllers → Services                          │
│                                                                        │
│  ┌──────────────┐   ┌─────────────────┐   ┌────────────────────────┐   │
│  │ LogController│   │ SearchController│   │ AnalyticsController    │   │
│  └──────┬───────┘   └────────┬────────┘   └───────────┬────────────┘   │
│         │                    │                        │                │
│         ▼                    ▼                        ▼                │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  LuceneLogService  ←→  LogQueryParser  ←→  LuceneIndexManager    │  │
│  │        │                                        │ (writers/readers)│  │
│  │        └──────────────► LogAnalyticsService ◄───┘                │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                        │
│  ┌───────────────┐    ┌──────────────┐    ┌────────────────────────┐   │
│  │ AlertScheduler│───►│ AlertService │───►│ Webhook / Email        │   │
│  └───────────────┘    └──────┬───────┘    └────────────────────────┘   │
│                              │ reads rules (JPA)                        │
└──────────────────────────────┼─────────────────────────────────────────┘
                               ▼
                    ┌────────────────────┐
                    │    PostgreSQL      │  tenants · alert_rules
                    │                    │  log_metadata · alert_events
                    └────────────────────┘
                               ▲
   gRPC :9090                  │ save metadata (optional)
┌──────────────┐               │
│ Microservices│──SendLogs────►│
└──────────────┘
```

---

## 3. Ingestion flow (write path)

```
Producer            gRPC Server        LogServiceImpl     IndexManager        LiveTail
   │                    │                    │                  │                 │
   │ SendLogs(batch)    │                    │                  │                 │
   ├───────────────────►│                    │                  │                 │
   │                    │ bind tenant        │                  │                 │
   │                    │ (metadata)         │                  │                 │
   │                    ├───────────────────►│                  │                 │
   │                    │                    │ validate batch   │                 │
   │                    │                    │ indexBatch(docs) │                 │
   │                    │                    ├─────────────────►│                 │
   │                    │                    │                  │ addDocuments()  │
   │                    │                    │                  │ (in-memory buf) │
   │                    │                    │◄─────────────────┤                 │
   │                    │                    │ broadcast?       │                 │
   │                    │                    ├──────────────────────────────────►│
   │                    │◄───────────────────┤                  │                 │
   │◄───────────────────┤ LogResponse        │                  │                 │
   │                    │                    │                  │                 │
   │                    │                    │   (async) commit / refresh every N ms
   │                    │                    │                  ├──► Directory    │
```

Key properties:
1. **Batching.** `SendLogs` carries many `LogRecord`s so per-RPC overhead is amortised.
   A single `IndexWriter.addDocuments()` call is far cheaper than N `addDocument()` calls.
2. **Non-blocking commit.** `IndexWriter` keeps documents in an in-memory buffer; a
   background flusher calls `commit()`/`maybeRefresh()` on an interval
   (`index.commit.interval.ms`, default 1000 ms) and by document count
   (`index.max.buffered.docs`).
3. **Backpressure.** A bounded `BlockingQueue` sits between gRPC threads and the writer
   thread. When full, ingestion applies backpressure instead of OOMing.
4. **Tenant scoping.** The interceptor stores the tenant, so documents are written into that
   tenant's index and carry a redundant `tenant_id` field for defence in depth.

---

## 4. Search flow (read path)

```
Client         SearchController     LuceneLogService     LogQueryParser     IndexSearcher
  │                  │                    │                    │                │
  │ POST /api/search │                    │                    │                │
  ├─────────────────►│                    │                    │                │
  │                  │ search(tenant,req) │                    │                │
  │                  ├───────────────────►│                    │                │
  │                  │                    │ parse(query)       │                │
  │                  │                    ├───────────────────►│                │
  │                  │                    │◄───────────────────┤ BooleanQuery   │
  │                  │                    │                    │ (+ tenant MT)  │
  │                  │                    │ search(query,limit)│                │
  │                  │                    ├─────────────────────────────────────►│
  │                  │                    │◄─────────────────────────────────────┤
  │                  │                    │ build facets/histogram              │
  │                  │◄───────────────────┤                                     │
  │◄─────────────────┤ {hits, facets, took}                                    │
```

A `NRT (near-real-time) DirectoryReader` is cached and refreshed on the commit interval,
so freshly indexed documents become searchable within ~1 s without reopening the reader
per query (reader reuse is what keeps p95 < 50 ms).

---

## 5. Alerting flow

```
AlertScheduler (every 60s)
     │
     ▼
AlertService.checkAlerts()
     │  for each enabled rule (per tenant)
     ▼
runRule(rule)
     │  build query from rule.query (e.g. level:ERROR AND service:billing-api)
     │  count = searchService.count(tenant, rule.query, window)
     ▼
threshold breached? ──no──► if previously firing → mark CLEARED, remove from active map
     │yes
     ▼
if not already firing:
     ├─ persist AlertEvent(status=FIRING)
     ├─ WebhookNotifier.post(payload)
     └─ EmailService.sendAlertEmail(...)
```

Deduplication uses an in-memory `Map<ruleId, IncidentState>` so a single incident produces
exactly one notification until the condition clears (and re-arms for the next incident).
`alert_events` provides an auditable history.

---

## 6. Threading model

| Pool | Default | Purpose |
|------|---------|---------|
| gRPC Netty event loop | Netty default | Network I/O |
| gRPC executor | cached | Handler invocation |
| `index-writer-{tenant}` | 1 per tenant | Serialised document writes (Lucene requires single writer per index) |
| `index-commit` | 1 shared | Periodic commit / NRT refresh |
| `alert-eval` | 2 | Rule evaluation |
| `webhook` | 4 | Outbound HTTP notification |

Lucene `IndexWriter` is **not** thread-safe for construction but is thread-safe for
`addDocument`; still, LogStream funnels writes through a single writer thread per tenant to
make batching and commit control deterministic.

---

## 7. Failure & consistency notes

- **Crash between commits** loses only the in-memory buffer (bounded by `max.buffered.docs`).
  Reduce the commit interval for lower data-loss windows at a small throughput cost.
- **Webhook failures** are retried with exponential backoff; a permanent failure is recorded
  on the `AlertEvent`.
- **Reader staleness** is bounded by the commit interval by design (NRT).
- **Tenant index corruption** is isolated per tenant; other tenants are unaffected.
