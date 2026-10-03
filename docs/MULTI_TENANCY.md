# LogStream — Multi-Tenancy Architecture

LogStream is a **multi-tenant** observability platform: one deployment serves many
independent organizations ("tenants"), each with their own logs, alert rules, dashboards
and live-tail stream. This document defines the isolation model, the guarantees, and the
implementation requirements.

---

## 1. Goals & guarantees

| Guarantee | Description |
|-----------|-------------|
| **Data isolation** | A tenant can never read, search, facet, or live-tail another tenant’s logs. |
| **Metadata isolation** | Alert rules and events are scoped to their owning tenant. |
| **Resource fairness** | A noisy tenant cannot starve others (bounded queues, per-tenant writers). |
| **Auditability** | Every stored log carries its `tenant_id`; every alert event records the tenant. |
| **Provisioning** | Creating a tenant provisions its index and default settings atomically. |

**Non-goals:** per-tenant physical hardware, per-tenant encryption keys (future work),
cross-tenant search (explicitly forbidden).

---

## 2. Tenant identity

- `tenant_id` is a short, stable, URL-safe string: `^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$`.
- It is **not** the database surrogate key; the surrogate `id` never leaves PostgreSQL.
- It is supplied by:
  - REST: `X-Tenant-Id` header (or a resolved value from auth claims later).
  - gRPC: `tenant-id` metadata key (carried in the proto `LogRecord.tenant_id` as well).
  - WebSocket: `?tenant=<id>` query parameter.

Request flow for tenant resolution:

```
HTTP ──► TenantContextFilter ──► TenantContext (ThreadLocal) ──► Services ──► Lucene
gRPC ──► TenantServerInterceptor ──► Context.key(TENANT) ──► Services ──► Lucene
WS   ──► HandshakeInterceptor ──► session attributes ──► broadcast filter
```

Every layer **defaults to `default`** only when no tenant is present, and that default
tenant is itself fully isolated (it is not a “global” tenant).

---

## 3. Isolation model

LogStream supports two isolation levels; **physically isolated (per-tenant index)** is the
default and recommended.

### 3.1 Physical isolation — default

```
logs/lucene-index/
├── default/   ← tenant "default"
├── acme/      ← tenant "acme"
└── globex/    ← tenant "globex"
```

- Each tenant gets its own `Directory`, `IndexWriter`, and cached `DirectoryReader`.
- A query is issued **only** against the resolved tenant’s index — cross-tenant leakage is
  structurally impossible.
- Blast radius: index corruption, merge storms, or resource exhaustion are contained to one
  tenant.
- Cost: one writer/reader per tenant (see capacity notes below).

### 3.2 Logical isolation — shared index (opt-in for many small tenants)

- A single index where **every document carries `tenant_id`**.
- `LogQueryParser` is the only query builder and **unconditionally** adds
  `Occur.FILTER` on `tenant_id`.
- `tenant_id` also becomes a Lucene **facet dimension** so aggregations can never bleed.
- This mode requires the structural guard in §4; it must never be reachable by raw
  `QueryParser.parse()` from request input.

### 3.3 Choosing

| Signal | Choose |
|--------|--------|
| < 50 tenants, some large | Physical |
| 1000s of tiny tenants | Shared + filter, or shard groups |
| Strict compliance (PHI/PCI) | Physical (optionally per-tenant disk/encryption) |

---

## 4. Enforcement (defence in depth)

Isolation is enforced at **four** layers; each is independently testable.

1. **Context binding** — `TenantContextFilter` / `TenantServerInterceptor` set the tenant
   for the request scope and clear it in `finally` (prevent ThreadLocal leakage across
   pooled threads).
2. **Query construction** — all query building goes through `LogQueryParser`, which
   requires a `tenant` argument and appends the tenant clause internally. Request input is
   never passed directly to `QueryParser`.
3. **Document tagging** — every indexed `Document` includes `tenant_id` even in physical
   mode, so a future migration to shared mode (or an accidental cross-index read) still
   filters correctly.
4. **Repository scoping** — tenant-scoped JPA entities are only accessed via
   `...ByTenantId...` methods. `findAll()` on tenant entities is restricted to admin code.

```java
// Layer 2 — the single choke point
public final class LogQueryParser {
    private final StandardAnalyzer analyzer;

    public Query parse(String expr, String tenant) {
        BooleanQuery.Builder root = new BooleanQuery.Builder();
        root.add(parseExpression(expr), Occur.MUST);
        root.add(new TermQuery(new Term("tenant_id", tenant)), Occur.FILTER); // never optional
        return root.build();
    }
}
```

---

## 5. Data model

```
tenants ──1:N──> alert_rules ──1:N──> alert_events
   │
   └──1:1 (logical)──> Lucene index directory  OR  tenant_id filter over shared index
```

- `tenants.tenant_id` is the join key used everywhere; enforce with a unique index.
- `alert_rules.tenant_id` and `alert_events.tenant_id` are **denormalised** (not just on
  the rule) so events can be listed per tenant without a join.
- Foreign keys use `ON DELETE CASCADE` from rule → events, and tenant deletion is a
  deliberate admin operation that also drops the Lucene directory.

---

## 6. Provisioning & lifecycle

### 6.1 Create tenant

```java
@Transactional
public Tenant createTenant(String tenantId, String name, String webhookUrl) {
    validateFormat(tenantId);
    if (tenantRepository.existsByTenantId(tenantId)) throw new ConflictException(...);
    Tenant t = tenantRepository.save(new Tenant(tenantId, name, webhookUrl));
    indexManager.createIndex(tenantId);        // idempotent
    return t;
}
```

`createIndex` must be idempotent (`OpenMode.CREATE_OR_APPEND`) and safe under concurrency
(guard with a `ConcurrentHashMap` of tenants → writer).

### 6.2 Delete tenant

1. Mark `active = false` (stop ingestion/alerting immediately).
2. After a grace period, delete rules/events, then close writers/readers and delete the
   Lucene directory.

### 6.3 Rate limits & quotas (recommended)

| Quota | Scope | Enforcement |
|-------|-------|-------------|
| Ingest rate | per tenant | token bucket before enqueue |
| Index size | per tenant | periodic `size / max_docs` check |
| Alert rules | per tenant | count check on create |
| Concurrent searches | per tenant | semaphore in search service |

---

## 7. Alerting in multi-tenant mode

- `AlertScheduler` iterates **enabled rules grouped by tenant**.
- Evaluation runs the rule’s stored query inside the tenant’s index (never global).
- Dedup state is keyed by `(tenantId, ruleId)` to avoid collisions between tenants that
  happen to share numeric rule ids in different databases.
- Webhook payloads include the `tenantId` so downstream tooling can route correctly.

---

## 8. Live Tail isolation

- On handshake, read `?tenant=acme`, validate the tenant exists, store it in session
  attributes, and reject unknown tenants.
- `LogWebSocketHandler.broadcast(log, tenant)` sends **only** to sessions bound to that
  tenant.
- Sessions are held in a `ConcurrentHashMap<String tenant, Set<WebSocketSession>>`.

---

## 9. Security considerations

- **AuthN/AuthZ (future):** the `X-Tenant-Id` header must be derived from an authenticated
  principal, not trusted blindly. Until then, document that the header is a *routing* key,
  not an authorization credential, and keep the API on a trusted network.
- **Header injection:** validate `tenant_id` against the registry; unknown tenants are
  rejected (ingestion) or yield empty results (search), never a scan of all indexes.
- **ThreadLocal leakage:** always `TenantContext.clear()` in a `finally` and/or use
  `Context` propagation for gRPC.
- **Enumeration:** do not return 404-with-different-body for existing vs non-existing
  tenants to unauthenticated callers.

---

## 10. Testing the isolation (required)

1. **Search:** index docs for tenants A and B; query as A with a query that also matches B;
   assert every hit and every facet bucket belongs to A.
2. **Aggregation:** assert histogram totals for A exclude B’s volume.
3. **Live Tail:** connect two sockets for A and B; broadcast to A; assert only A receives.
4. **Alerts:** rules for A and B with identical numeric ids; assert events are not mixed.
5. **Negative:** attempt to bypass the tenant filter by injecting `tenant_id:B` into the
   user query — the parser must still AND the real tenant, so the result is empty.

---

## 11. Operational checklist

- [ ] `tenant_id` format validated on every ingress.
- [ ] `tenant_id` present in every indexed document.
- [ ] All query paths go through `LogQueryParser` (no raw parsing of user input).
- [ ] All tenant JPA access uses `...ByTenantId...` methods.
- [ ] WebSocket sessions bound to a tenant.
- [ ] Dedup keys include the tenant.
- [ ] Isolation test suite green in CI.
- [ ] Per-tenant quotas configured and monitored.
