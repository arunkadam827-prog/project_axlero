# LogStream — Remaining Implementation Instructions

This document is the working plan for finishing LogStream. It is organised **week by week**,
matching the project brief, and lists concrete, actionable engineering tasks with acceptance
criteria. Items already implemented in this repository are marked ✅; remaining work is
marked ☐.

Cross-references:
- Index design → [`INDEXING_STRATEGY.md`](INDEXING_STRATEGY.md)
- Tenant model → [`MULTI_TENANCY.md`](MULTI_TENANCY.md)
- Component map → [`ARCHITECTURE.md`](ARCHITECTURE.md)

---

## Week 1 — Ingestion setup & dashboard scaffolding

### 1.1 Protobuf schema (✅ done)
`log_service.proto` defines `LogRecord`, `LogBatchRequest`, `LogResponse`,
`SearchRequest`, `SearchResponse`, `HistogramRequest/Bucket`, `StatsRequest`.

Tasks:
- ✅ Add `tenant_id`, `trace_id`, `response_time`, `error_code`, `metadata` map.
- ☐ Add a `stream Logs(stream LogBatchRequest) returns (IngestAck)` **client-streaming RPC**
  so producers can push continuously without round-tripping the response.
- ☐ Bump `proto` package version and regenerate (`mvn generate-sources`).

### 1.2 gRPC server (✅ baseline done)
Tasks:
- ☐ Register `TenantServerInterceptor` in [`GrpcServerConfig`](../logstream-main/backend/logstream-backend/src/main/java/logstream_backend/config/GrpcServerConfig.java)
  (see §6.2).
- ☐ Configure keepalive + `maxInboundMessageSize(16MB)`.
- ☐ Enable gRPC reflection in non-prod for `grpcurl` ergonomics.

### 1.3 High-throughput ingestion (☐ partial)
The naive `new IndexWriter(...)` per document in the original code must be replaced.

Tasks:
- ☐ Introduce `LuceneIndexManager` (one writer per tenant, batch flush, NRT refresh).
- ☐ Decouple gRPC threads from the writer via a bounded `ArrayBlockingQueue`.
- ☐ Add `IngestMetrics` (`logs_ingested_total`, `ingest_queue_depth`, `index_commit_millis`).
- **Acceptance:** sustained ≥ **10,000 logs/sec** for 60 s on a single machine; queue depth
  stays bounded; no `OutOfMemoryError`.

### 1.4 Dashboard scaffolding (✅ baseline done)
Tasks:
- ☐ Implement the missing **Logs** page (currently imported by `App.tsx` but absent) — §5.
- ☐ Add loading/empty/error states consistently across pages.
- **Acceptance:** `npm run dev` and `npm run build` succeed; no missing-module errors.

---

## Week 2 — Lucene integration & query interface

### 2.1 Indexing logic (☐ refactor)
Tasks:
- ☐ Map each `LogRecord` to a Lucene `Document` with the field types defined in
  [`INDEXING_STRATEGY.md`](INDEXING_STRATEGY.md) §3.
- ☐ Parse ISO timestamps to `LongPoint` `epoch_millis`; store `timestamp` text for display.
- ☐ Use `TextField` + `StandardAnalyzer` (or a log-specific analyzer) for `message`.
- ☐ Use `StringField` for `level`, `service`, `host`, `trace_id`, `error_code`, `tenant_id`.
- ☐ Index `response_time` as `IntPoint`/`LongPoint` for range queries.
- **Acceptance:** a document is searchable within 1 s of ingestion (NRT).

### 2.2 Query interface (☐ implement)
Implement `LogQueryParser` supporting:
- field terms: `level:ERROR`, `service:billing-api`
- boolean: `AND`, `OR`, `NOT`, parentheses
- ranges: `response_time > 1000`, `response_time:1000 TO 2000`
- wildcards/phrases inside `message`

Tasks:
- ☐ Write `LogQueryParser.parse(String q, String tenant)` returning a tenant-scoped
  `BooleanQuery` (always AND a `TermQuery(tenant_id)`).
- ☐ Wire `POST /api/search` with body `{ "query": "...", "limit": 50, "from": 0 }`.
- ☐ Frontend `Logs` page builds and submits these queries.
- **Acceptance:** `level:ERROR AND service:billing-api AND response_time > 1000` returns
  correct results; verified by unit tests.

### 2.3 Mid-project review deliverables
- ☐ **Throughput audit:** JMeter/`ghz` load script + recorded results proving 10k/s.
- ☐ **Search validation:** benchmark harness over a generated 1,000,000-document index
  proving p95 < 50 ms.
- ☐ Save both reports under `docs/reports/`.

---

## Week 3 — Aggregations & visualisation

### 3.1 Aggregations (☐ implement)
Tasks:
- ☐ `LogAnalyticsService.histogram(tenant, query, from, to, interval)` — bucket by
  `epoch_millis` using `LongRangeFacetCounts` or manual `LongPoint` range runs.
- ☐ `countByLevel`, `countByService` using `StringField` term facets.
- ☐ `stats(tenant)` — totals + level counters in one pass.
- **Acceptance:** histogram for 24 h @ 1M docs returns in < 150 ms.

### 3.2 Data visualisation (☐ wire up)
Tasks:
- ☐ Replace static arrays in [`LogVolumeChart`](../logstream-main/frontend/src/components/charts/LogVolumeChart.tsx)
  and [`ErrorRateChart`](../logstream-main/frontend/src/components/charts/ErrorRateChart.tsx) with
  API data from `/api/analytics/*`.
- ☐ Add a level/service distribution pie or bar to [`Analytics`](../logstream-main/frontend/src/pages/analytics/Analytics.tsx).
- ☐ Add a volume histogram above results on the Logs page.
- ☐ Add an interval selector (1m / 5m / 1h) that re-queries the histogram.
- **Acceptance:** charts update on interval/tenant change and render real data.

---

## Week 4 — Alerting system & polish

### 4.1 Alerting engine (☐ extend)
Current [`AlertService`](../logstream-main/backend/logstream-backend/src/main/java/logstream_backend/service/AlertService.java)
counts from PostgreSQL. Migrate to querying Lucene (fast, tenant-scoped) and add webhooks.

Tasks:
- ☐ Add `query`, `webhookUrl`, `notifyEmail`, `severity` to `AlertRule`.
- ☐ Evaluate rules via `LogAnalyticsService.count(tenant, rule.query, window)`.
- ☐ Add `WebhookNotifier` with retry + timeout; call it on FIRING transition.
- ☐ Persist `AlertEvent` (FIRING/CLEARED) with counts and timestamps.
- ☐ Harden dedup: one notification per incident, re-arm after clear.
- **Acceptance:** an injected 150-ERROR burst triggers exactly one webhook+email; clearing
  re-arms the rule.

### 4.2 Live Tail (✅ baseline done)
Tasks:
- ☐ Make the WebSocket tenant-aware (`/ws/logs?tenant=acme`) so clients only receive
  their tenant's stream.
- ☐ Send from a bounded per-connection queue to avoid slow-consumer backpressure.
- ☐ Decouple persistence from broadcast: broadcast **before** indexing so Live Tail is faster
  than the search index (by design).
- **Acceptance:** Live Tail latency < 500 ms; no dropped messages under 10k/s with 5 clients.

### 4.3 Refine & polish (☐)
Tasks:
- ☐ Fix the alert field-name mismatch between frontend (`windowMinutes`) and backend
  (`timeWindowMinutes`) — standardise on `timeWindowMinutes` in both.
- ☐ Add `@CrossOrigin`-safe CORS via config only (remove scattered annotations).
- ☐ Add pagination + total-hit count to search responses.
- ☐ Add tenant selector + persisted selection in the UI.
- ☐ Add “Saved Queries” and “Saved alert from query” shortcuts on the Logs page.

---

## 5. Missing Logs page (blocking build)

`App.tsx` imports `./pages/logs/Logs` but `src/pages/logs/` contains no `Logs.tsx`, so the
frontend cannot build. Implement `Logs.tsx` + `Logs.css` with:
1. A query input (placeholder `level:ERROR AND service:billing-api AND response_time > 1000`).
2. Level + service filter dropdowns (optional convenience).
3. A time-range selector.
4. Submit → `POST /api/search`; render hit count, elapsed ms, and a result table
   (time, level badge, service, message, host, response time).
5. A volume histogram above the results driven by `/api/analytics/histogram`.

---

## 6. Implementation patterns

### 6.1 Tenant scoping in REST
```java
// TenantContextFilter (OncePerRequestFilter) reads X-Tenant-Id -> TenantContext
String tenant = TenantContext.get();          // set by filter
List<LogHit> hits = luceneLogService.search(tenant, q, level, service, limit);
TenantContext.clear();                        // in finally
```

### 6.2 Tenant scoping in gRPC
```java
public class TenantServerInterceptor implements ServerInterceptor {
  public static final Metadata.Key<String> TENANT =
      Metadata.Key.of("tenant-id", Metadata.ASCII_STRING_MARSHALLER);

  @Override
  public <ReqT,RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT,RespT> call, Metadata headers, ServerCallHandler<ReqT,RespT> next) {
    String tenant = headers.get(TENANT);
    if (tenant == null || tenant.isBlank()) tenant = "default";
    final String t = tenant;
    Context ctx = Context.current().withValue(TENANT_CTX_KEY, t);
    return Contexts.interceptCall(ctx, call, headers, next);
  }
}
```

### 6.3 NRT reader reuse
```java
DirectoryReader reader = DirectoryReader.openIfChanged(oldReader); // cheap refresh
IndexSearcher searcher = new IndexSearcher(reader);
```

### 6.4 Batch commit policy
```java
if (pendingDocs >= maxBufferedDocs || now - lastCommit > commitIntervalMs) {
    writer.commit();
    maybeRefreshReader();
}
```

---

## 7. Testing strategy

| Level | Tool | Coverage |
|-------|------|----------|
| Unit | JUnit 5 + AssertJ | `LogQueryParser`, range/facet logic |
| Integration | Spring Boot Test | gRPC round-trip, REST controllers, tenant isolation |
| Load | ghz / JMeter | ingestion throughput, search latency |
| Frontend | Vitest + Testing Library | Logs search flow, chart data mapping |

Tenant-isolation test (mandatory): index docs for tenant A and B, query as A, assert zero
B documents are ever returned — including via facets and histogram buckets.

---

## 8. Definition of done (final review)

- [ ] 10,000 logs/sec ingestion proven and reproducible.
- [ ] 1M-doc search p95 < 50 ms proven and reproducible.
- [ ] Complex query language fully supported and unit-tested.
- [ ] Histograms/facets power all ECharts on the dashboard.
- [ ] One query-based alert rule fires exactly one webhook + email per incident.
- [ ] Live Tail streams in near-real-time, tenant-isolated.
- [ ] Every read/write path is tenant-scoped and proven by tests.
- [ ] `mvn clean verify` and `npm run build` both green.
