# LogStream — Database & Indexing Strategy

LogStream deliberately separates **search** from **metadata**:

| Store | Purpose | Query style |
|-------|---------|-------------|
| **Apache Lucene** | Raw log records (high volume) | full-text + range + facet, sub-100 ms |
| **PostgreSQL** | Metadata (tenants, rules, events, optional recent-log mirror) | transactional CRUD |

This document specifies the indexing strategy for both.

---

## 1. Why not index raw logs in PostgreSQL?

- `LIKE '%timeout%'` cannot use a B-tree index → full table scan.
- Even with `pg_trgm` GIN indexes, high-cardinality combos
  (`level AND service AND response_time > 1000`) degrade quickly.
- Ingestion at 10k/s overwhelms WAL + autovacuum.
- Faceting (counts per level/service/minute) requires repeated scans/rollups.

Lucene’s **inverted index** answers term lookups in O(matching docs) and supports
`IntPoint`/`LongPoint` range filters natively. PostgreSQL is retained for the small,
mutable, relational metadata that Lucene is bad at.

---

## 2. PostgreSQL strategy

### 2.1 Tables

```sql
-- Tenants
CREATE TABLE tenants (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    VARCHAR(64)  NOT NULL UNIQUE,   -- the external key
    name         VARCHAR(255) NOT NULL,
    webhook_url  VARCHAR(1024),
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    active       BOOLEAN      NOT NULL DEFAULT true
);

-- Optional lightweight metadata mirror (NOT used for search)
CREATE TABLE log_metadata (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     VARCHAR(64)  NOT NULL,
    trace_id      VARCHAR(64),
    level         VARCHAR(16),
    service       VARCHAR(128),
    response_time INTEGER,
    log_time      TIMESTAMP,
    created_at    TIMESTAMP    NOT NULL DEFAULT now()
);

-- Alert rules
CREATE TABLE alert_rules (
    id                 BIGSERIAL PRIMARY KEY,
    tenant_id          VARCHAR(64)  NOT NULL,
    name               VARCHAR(255) NOT NULL,
    query              VARCHAR(1024) NOT NULL,     -- lucene-style expression
    time_window_minutes INTEGER     NOT NULL DEFAULT 5,
    threshold          INTEGER      NOT NULL DEFAULT 100,
    severity           VARCHAR(16)  DEFAULT 'ERROR',
    webhook_url        VARCHAR(1024),
    notify_email       VARCHAR(255),
    enabled            BOOLEAN      NOT NULL DEFAULT true,
    created_at         TIMESTAMP    NOT NULL DEFAULT now()
);

-- Alert audit trail
CREATE TABLE alert_events (
    id             BIGSERIAL PRIMARY KEY,
    rule_id        BIGINT      NOT NULL REFERENCES alert_rules(id) ON DELETE CASCADE,
    tenant_id      VARCHAR(64) NOT NULL,
    status         VARCHAR(16) NOT NULL,           -- FIRING | CLEARED
    observed_count INTEGER     NOT NULL,
    threshold      INTEGER     NOT NULL,
    message        TEXT,
    triggered_at   TIMESTAMP   NOT NULL DEFAULT now()
);
```

### 2.2 Indexes

```sql
-- Tenant lookups dominate every query path.
CREATE UNIQUE INDEX ux_tenants_tenant_id            ON tenants(tenant_id);

-- Alert rule evaluation: fetch enabled rules per tenant.
CREATE INDEX ix_alert_rules_tenant_enabled          ON alert_rules(tenant_id, enabled);

-- Alert history: "latest events for tenant/rule".
CREATE INDEX ix_alert_events_rule_time              ON alert_events(rule_id, triggered_at DESC);
CREATE INDEX ix_alert_events_tenant_time            ON alert_events(tenant_id, triggered_at DESC);

-- Metadata mirror (only if you keep it): composite for the common filter.
CREATE INDEX ix_log_metadata_tenant_time            ON log_metadata(tenant_id, log_time DESC);
CREATE INDEX ix_log_metadata_tenant_level_service   ON log_metadata(tenant_id, level, service);
```

### 2.3 Partitioning (when metadata grows)

Partition `log_metadata` and `alert_events` by month using declarative partitioning:

```sql
CREATE TABLE log_metadata_2026_10 PARTITION OF log_metadata
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
```

Drop old partitions for retention instead of `DELETE` (instant, no bloat).

### 2.4 Retention & maintenance

| Data | Retention | Mechanism |
|------|-----------|-----------|
| Lucene index | 7–30 days | time-based merge policy + segment deletion job |
| `log_metadata` | 7 days | drop monthly partitions |
| `alert_events` | 90 days | drop old partitions |
| `alert_rules` / `tenants` | indefinite | — |

Run `VACUUM (ANALYZE)` on metadata tables nightly; they are small.

### 2.5 JPA notes

- Add `tenant_id` to every query method:
  ```java
  List<AlertRule> findByTenantIdAndEnabledTrue(String tenantId);
  List<AlertEvent> findByTenantIdOrderByTriggeredAtDesc(String tenantId);
  ```
- Never expose `findAll()` on tenant-scoped entities to request handling — it is a
  cross-tenant leak. Keep it only for admin tooling.

---

## 3. Lucene indexing strategy

### 3.1 Directory & layout

```
logs/lucene-index/
├── default/          # index for tenant "default"
│   ├── segments_*
│   ├── _0.cfs
│   └── write.lock
├── acme/             # per-tenant index (physical isolation)
└── globex/
```

- Use **`MMapDirectory`** on 64-bit OS for fast random reads.
- If indexes must be shared for cost reasons, use a **single index with a mandatory
  `tenant_id` filter** instead (see §4). Default recommendation: per-tenant directory
  for strong isolation, shared index for many small tenants.

### 3.2 Field mapping

| Field | Lucene type | Indexed | Stored | Notes |
|-------|-------------|---------|--------|-------|
| `tenant_id` | `StringField` | ✅ | ✅ | always AND-ed as a filter |
| `timestamp` | `StringField` | ✅ | ✅ | display value |
| `epoch_millis` | `LongPoint` + `NumericDocValuesField` | ✅ | — | range + facet bucketing |
| `level` | `StringField` | ✅ | ✅ | UPPERCASE canonical (`ERROR`,`WARN`,`INFO`,`DEBUG`) |
| `service` | `StringField` | ✅ | ✅ | exact match facet |
| `host` | `StringField` | ✅ | ✅ | exact match |
| `trace_id` | `StringField` | ✅ | ✅ | correlation |
| `error_code` | `StringField` | ✅ | ✅ | exact match |
| `response_time` | `IntPoint` (+ `SortedNumericDocValuesField`) | ✅ | ✅ | range filter + sorting |
| `message` | `TextField` | ✅ | ✅ | analyzed full-text |

Storing `NumericDocValuesField` for `epoch_millis`/`response_time` is what makes
sorting and range-facets fast without re-reading stored fields.

### 3.3 Analyzer choice

- Default: `StandardAnalyzer` (Unicode tokenisation, lowercasing, stopwords).
- Recommended upgrade: a **custom `LogAnalyzer`** chaining
  `StandardTokenizer → LowerCaseFilter → WordDelimiterGraphFilter → TrimFilter`,
  plus a `KeywordRepeatFilter` if you need partial matching.
- Keep `message` analyzed; keep every identifier field `StringField` (not analyzed) so
  `level:ERROR` and `service:billing-api` are exact terms.

### 3.4 Writing strategy (throughput)

1. **One `IndexWriter` per tenant**, created once, kept open. Never construct an
   `IndexWriter` per document (the original bug) — it rewrites/opens segments each time.
2. **`addDocuments(List<Document>)`** for batches instead of `addDocument` in a loop.
3. `IndexWriterConfig`:
   ```java
   cfg.setRAMBufferSizeMB(256);            // larger buffer -> fewer flushes
   cfg.setMaxBufferedDocs(100_000);        // prefer RAM-based flush
   cfg.setUseCompoundFile(false);          // less merge write amplification for append-heavy
   cfg.setCommitOnClose(false);
   cfg.setOpenMode(OpenMode.CREATE_OR_APPEND);
   ```
4. **Commit policy:** commit every 1 s **or** every N docs, whichever first. Commits are
   `fsync`-heavy — do not commit per document.
5. **Merge policy:** default `TieredMergePolicy` is fine; for append-only, log-merge can
   reduce write amplification. Cap `setSegmentsPerTier(10)` and monitor
   `index_merge_millis`.
6. **Deletes/TTL:** use `updateDocument(Term, doc)` only if a unique id exists; otherwise
   delete by time range with `LongPoint.newRangeQuery` + `deleteDocuments`.

### 3.5 Reading strategy (latency)

- Cache an **`DirectoryReader`** per tenant; refresh with
  `DirectoryReader.openIfChanged(old)` on the commit interval (≈1 s NRT).
- Reuse a warm `IndexSearcher`; a cold searcher costs +30–80 ms on first query.
- **Filters first:** add `tenant_id`, `level`, `service` as `BooleanClause.Occur.FILTER`
  (no scoring) so the `IntPoint` range on `response_time` only scans the filtered subset.
- Use `TopFieldCollector`/`TopScoreDocCollector` with `TotalHitCountCollector` when you
  need an exact total without retrieving all docs.
- For aggregations, use `FacetsCollector` + `SortedSetDocValuesFacetCounts`
  (level/service) and `LongRangeFacetCounts` (time histogram). Avoid re-querying per bucket.

### 3.6 Sharding & scaling

- Vertical first: per-tenant index + big RAM buffer + NRT reader covers 10k/s easily.
- Horizontal: shard by `tenant_id` hash across nodes; route ingestion and search by
  tenant. A tenant’s results must never be merged across shards without re-checking the
  tenant filter.
- Time-based indices (`logs-2026-10-02`) simplify retention: close + delete entire
  directories.

### 3.7 Segment lifecycle

```
in-memory RAM buffer
      │ flush (RAMBufferSizeMB / maxBufferedDocs)
      ▼
small segments → TieredMergePolicy merges → large segments
      │ commit() (fsync)
      ▼
durable + NRT-visible after refresh
```

Monitor: `segments_count`, `deleted_docs`, `merge_time_ms`. Keep
`deleted_docs / numDocs < 20%`.

---

## 4. Shared-index vs per-tenant-index

| Aspect | Per-tenant index | Shared index + filter |
|--------|------------------|-----------------------|
| Isolation | Physical (strongest) | Logical (filter) |
| Ops cost | N readers/writers | 1 reader/writer |
| Best for | Few large tenants | Many small tenants |
| Delete a tenant | Drop directory | Delete-by-term (costly) |
| Risk | Resource per tenant | Filter must be unbypassable |

**Rule:** make `tenant_id` mandatory in `LogQueryParser` and add a test that proves no
API path can omit it. Shared mode is only acceptable when the filter is structurally
guaranteed (see [`MULTI_TENANCY.md`](MULTI_TENANCY.md) §4).

---

## 5. Capacity planning cheat-sheet

| Factor | Guidance |
|--------|----------|
| Doc size | ~0.5–2 KB stored → ×1.3 on disk |
| Index size | ≈ raw stored size × 1.2–1.5 (indexed + docvalues) |
| RAM | `RAMBufferSizeMB × tenants` + reader overhead (~50 MB/tenant) |
| Disk IOPS | Commit frequency × tenants drives fsync load |
| 1M docs | ≈ 1–2 GB index, 1–3 s full merge |
| 10k/s sustained | 1 writer thread + 256 MB buffer is sufficient |

---

## 6. Benchmark commands

```bash
# index 1,000,000 synthetic docs, then measure search latency
curl -X POST localhost:8080/api/dev/benchmark/seed -d '{"tenant":"bench","count":1000000}'
curl -X POST localhost:8080/api/search -H 'X-Tenant-Id: bench' \
  -d '{"query":"level:ERROR AND service:billing-api AND response_time > 1000","limit":50}'

# gRPC throughput
ghz --insecure --proto src/main/proto/log_service.proto \
    --call logstream.LogService/SendLogs \
    -d @load/batch.json -z 60s -c 50 localhost:9090
```

Record results in `docs/reports/throughput.md` and `docs/reports/search-latency.md`.
