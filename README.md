# LogStream — Distributed Log Analytics & Alerting Platform

> Project 2 · Domain: **Observability & Big Data**
> An enterprise-grade, self-hosted log analytics platform (an "ELK-lite" built from scratch)
> that ingests millions of log lines per minute over **gRPC**, indexes them into embedded
> **Apache Lucene**, serves complex boolean/range queries in milliseconds, aggregates
> time-series data for dashboards, and evaluates alerting rules with webhook/email actions.

---

## 1. Why this project exists

Storing and searching terabytes of application logs in a relational database (PostgreSQL)
collapses under scan-heavy queries. A `WHERE message LIKE '%timeout%'` against 100M rows is a
full table scan. LogStream inverts that model: logs flow in as binary protobuf over gRPC,
are **inverted-indexed** in Lucene, and are queried through a purpose-built query language:

```
level:ERROR AND service:billing-api AND response_time > 1000
```

The backend returns the exact matching log lines (plus facets/histograms) in single-digit
milliseconds. PostgreSQL is retained **only** for metadata (alert rules, tenants) — never for
raw log search.

---

## 2. High-level architecture

```
                        ┌──────────────────────────────────────────────────────────┐
   Microservices        │                     LogStream Backend (Spring Boot)       │
   (producers)          │                                                          │
        │               │   ┌──────────────┐    ┌────────────────────────────┐     │
        │  protobuf     │   │ gRPC Server  │    │  Async Index Pipeline      │     │
        ├── SendLog ───►│──►│  :9090       │───►│  ingest → queue → writer   │     │
        │  SendLogs     │   │  interceptor │    │  (bounded batch + flush)   │     │
        │  (stream)     │   │  = tenant    │    └──────────────┬─────────────┘     │
        │               │   └──────────────┘                   │                   │
        │               │                                      ▼                   │
        │               │                     ┌──────────────────────────────┐    │
        │               │                     │  Apache Lucene (MMapDirectory)│    │
        │               │                     │  per-tenant index, NRT reader │    │
        │               │                     └──────────────┬───────────────┘    │
        │               │                                    │                     │
   React + ECharts ◄────┤  REST /api/**  ◄── SearchService ───┤ facets/histogram    │
   Dashboard            │                                    │                     │
        ▲               │   ┌────────────────┐               │                     │
        │  WebSocket    │   │ AlertScheduler  │── runs saved queries every minute  │
        └── Live Tail ◄─┤   │ (@Scheduled)    │──► webhook / email                 │
                        │   └────────────────┘                                    │
                        └──────────────────────────────────────────────────────────┘
              PostgreSQL: tenants, alert_rules, alert_events (metadata only)
```

Detailed diagrams, component responsibilities and request flows live in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

---

## 3. Technology stack

| Layer            | Technology                                                              |
|------------------|-------------------------------------------------------------------------|
| Ingestion        | Java 21, gRPC (Netty, protobuf 3), async batching                       |
| Search / Index   | Apache Lucene 10.3 (`lucene-core`, `-queryparser`, `-analysis-common`, `-facet`) |
| Backend API      | Spring Boot 3.5, Spring Web, Spring Data JPA, Spring Mail, Actuator      |
| Streaming        | Spring WebSocket (raw `TextWebSocketHandler`) for Live Tail             |
| Metadata store   | PostgreSQL 15                                                           |
| Frontend         | React 19, TypeScript, Vite, React Router, ECharts, React Query, Lucide  |
| Build            | Maven (backend), npm/Vite (frontend)                                    |

---

## 4. Repository layout

```
project_axlero/
├── docs/
│   ├── ARCHITECTURE.md          # component + sequence diagrams
│   ├── IMPLEMENTATION_PLAN.md   # week-wise remaining instructions
│   ├── INDEXING_STRATEGY.md     # PostgreSQL + Lucene indexing strategies
│   └── MULTI_TENANCY.md         # tenant isolation architecture
├── logstream-main/
│   ├── backend/logstream-backend/
│   │   ├── src/main/proto/log_service.proto
│   │   ├── src/main/java/logstream_backend/
│   │   │   ├── config/          # gRPC, WebSocket, CORS, tenant filter
│   │   │   ├── controller/      # REST: logs, search, analytics, alerts
│   │   │   ├── entity/          # LogEntity, AlertRule, AlertEvent, Tenant
│   │   │   ├── repository/
│   │   │   ├── service/         # Lucene, search, analytics, alerting
│   │   │   └── websocket/       # Live Tail broadcaster
│   │   └── src/main/resources/application.properties
│   └── frontend/
│       └── src/
│           ├── pages/           # overview, logs, live-tail, analytics, alerts, settings
│           ├── components/      # charts, sidebar, tenant selector
│           └── services/api.ts
└── postman/                     # API collection
```

---

## 5. Running locally

### 5.1 Prerequisites
- JDK 21
- Maven 3.9+
- Node.js 20+ / npm 10+
- PostgreSQL 15 running on `localhost:5432`

### 5.2 Database
```sql
CREATE DATABASE logstream;
-- Schema is auto-created by Hibernate (ddl-auto=update) plus the
-- recommended manual index DDL in docs/INDEXING_STRATEGY.md §2.
```

### 5.3 Backend
```bash
cd logstream-main/backend/logstream-backend
# configure DB credentials + SMTP in src/main/resources/application.properties
./mvnw clean spring-boot:run
```
Services started:
- REST API → http://localhost:8080
- gRPC ingestion → localhost:9090
- WebSocket Live Tail → ws://localhost:8080/ws/logs
- Health → http://localhost:8080/actuator/health

### 5.4 Frontend
```bash
cd logstream-main/frontend
npm install
npm run dev        # http://localhost:5173
```

### 5.5 Generate log traffic (quick smoke test)
Use the Postman collection in [`postman/`](postman/) or `grpcurl`:
```bash
grpcurl -plaintext -d '{"log":{"tenantId":"acme","timestamp":"2026-10-02T10:00:00Z","level":"ERROR","service":"billing-api","message":"db timeout","host":"node-1","responseTime":1420}}' \
  localhost:9090 logstream.LogService/SendLog
```

### 5.6 Troubleshooting
- **`NoClassDefFoundError: LogRepository` / bare class names at startup.**
  The IDE's Java compiler (ECJ in VS Code, or IntelliJ's built-in build) can write
  classes with unresolved descriptors into `target/classes`, overwriting Maven's output.
  Maven is the single source of truth here:
  - VS Code: `"java.autobuild.enabled": false` is set in [`.vscode/settings.json`](.vscode/settings.json).
  - IntelliJ: `MavenRunner.delegateBuildToMaven = true` is set in the workspace files.
  If it recurs, run `./mvnw clean compile` (or `mvnw.cmd -o clean compile -DskipTests`) once.
- **`BindException: Address already in use` on 8080 / 9090.**
  A previous instance is still running. Find and stop it:
  ```cmd
  netstat -ano | findstr LISTENING | findstr ":8080 :9090"
  taskkill /F /PID <pid> /T
  ```
- **Backend fails to start with a datasource error.** Ensure PostgreSQL is running on
  `localhost:5432` and the `logstream` database exists (see §5.2).

---

## 6. Core API surface

| Method     | Path                    | Purpose                                             |
|------------|-------------------------|-----------------------------------------------------|
| GET        | `/api/logs`             | Recent logs (`limit`), newest first                 |
| GET        | `/api/logs/count`       | Total indexed documents for the tenant              |
| GET        | `/api/logs/stats`       | Level distribution + dashboard summary counters     |
| GET        | `/api/logs/search`      | Query-language search (`q`, `level`, `service`, `limit`, `offset`) |
| GET        | `/api/logs/histogram`   | Log volume time-series for ECharts (`interval`, `minutes`) |
| GET        | `/api/logs/facets`      | Term distribution (`field`, `topN`, `minutes`)      |
| POST       | `/api/alerts/rules`     | Create an alert rule                                |
| GET        | `/api/alerts/rules`     | List tenant alert rules                             |
| PUT        | `/api/alerts/rules/{id}`| Update an alert rule                                |
| DELETE     | `/api/alerts/rules/{id}`| Delete an alert rule                                |
| GET        | `/api/alerts/current`   | Currently firing alerts (live state)                |
| GET        | `/api/alerts/history`   | Alert firing history (FIRING / CLEARED)             |
| POST       | `/api/alerts/evaluate`  | Force an immediate evaluation of the tenant's rules |

All REST calls accept tenant context via the `X-Tenant-Id` header (default `default`).
See [`docs/MULTI_TENANCY.md`](docs/MULTI_TENANCY.md).

Full request/response examples: [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md).

---

## 7. Documentation map

| Document | Contents |
|----------|----------|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Component, ingestion, search and alert sequence diagrams |
| [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md) | **Remaining week-by-week implementation instructions** |
| [`docs/INDEXING_STRATEGY.md`](docs/INDEXING_STRATEGY.md) | PostgreSQL indexing + Lucene index design & tuning |
| [`docs/MULTI_TENANCY.md`](docs/MULTI_TENANCY.md) | Tenant isolation model, guarantees, provisioning |

---

## 8. Performance targets (definition of done)

| Metric                                | Target                                      |
|---------------------------------------|---------------------------------------------|
| gRPC ingestion throughput             | ≥ 10,000 logs/sec on a single machine       |
| Search latency @ 1M docs              | < 50 ms p95 for filtered keyword queries    |
| Histogram aggregation latency         | < 150 ms for 24h @ 1M docs                  |
| Alert evaluation cadence              | every 60 s (configurable), idempotent       |
| Live Tail end-to-end latency          | < 500 ms from gRPC receipt to browser       |
