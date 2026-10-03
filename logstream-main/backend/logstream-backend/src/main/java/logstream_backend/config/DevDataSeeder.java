package logstream_backend.config;

import com.logstream.grpc.LogRequest;

import logstream_backend.entity.LogEntity;
import logstream_backend.repository.LogRepository;
import logstream_backend.search.LogDocumentMapper;
import logstream_backend.service.LuceneLogService;
import logstream_backend.websocket.LogWebSocketHandler;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Development-only data seeder.
 *
 * <p>
 * The dashboard, logs explorer, analytics charts and services page all read
 * from the Lucene index / PostgreSQL, so a fresh install renders only zeros
 * until real traffic arrives. Enabling ({@code logstream.dev.seed=true}) this
 * runner injects a realistic, multi-tenant sample dataset through the
 * <em>same</em> write path as production ingestion — PostgreSQL audit row,
 * buffered Lucene document (committed once), and a Live Tail WebSocket
 * broadcast — so every page shows meaningful values.
 * </p>
 *
 * <p>
 * It is guarded by {@link ConditionalOnProperty} and is therefore dormant
 * unless explicitly enabled; it never ships sample data in production.
 * </p>
 */
@Component
@ConditionalOnProperty(name = "logstream.dev.seed", havingValue = "true")
public class DevDataSeeder implements ApplicationRunner {

        /** Tenants that receive sample data (match the frontend tenant selector). */
        private static final List<String> TENANTS = List.of(
                        "default", "acme", "globex", "initech");

        private static final int LOGS_PER_TENANT = 60;

        /** How far back the seeded sample data is uniformly distributed. */
        private static final int SEED_WINDOW_MINUTES = 60;

        /**
         * The longest default rolling window any dashboard page requests (the
         * Overview histogram looks back 60 minutes). When the newest seeded row
         * is older than this, the charts render zeros and the sample data is
         * refreshed on the next start.
         */
        private static final int DASHBOARD_WINDOW_MINUTES = 90;

        private static final String[] SERVICES = {
                        "billing-api", "auth-service", "checkout-web",
                        "search-indexer", "notification-worker", "payment-gateway",
        };

        private static final String[] HOSTS = {
                        "node-1", "node-2", "node-3", "node-4", "node-5",
        };

        /** Weighted level pool — mostly INFO, with a meaningful error tail. */
        private static final String[] LEVELS = {
                        "INFO", "INFO", "INFO", "INFO", "INFO", "INFO",
                        "WARN", "WARN",
                        "ERROR", "ERROR",
                        "DEBUG",
        };

        private static final Map<String, String[]> MESSAGES = Map.of(
                        "INFO", new String[] {
                                        "Request completed successfully",
                                        "User session refreshed",
                                        "Cache hit for product catalogue",
                                        "Payment intent confirmed",
                                        "Scheduled reconciliation finished",
                        },
                        "WARN", new String[] {
                                        "Slow downstream response detected",
                                        "Connection pool nearing capacity",
                                        "Retry scheduled after transient failure",
                                        "Deprecated API version used by client",
                        },
                        "ERROR", new String[] {
                                        "Database timeout while executing query",
                                        "Upstream billing API returned 503",
                                        "Payment gateway connection refused",
                                        "Unhandled exception in checkout flow",
                        },
                        "DEBUG", new String[] {
                                        "Feature flag evaluated",
                                        "Serialized payload size recorded",
                                        "Trace span started",
                        });

        private static final Map<String, String[]> ERROR_CODES = Map.of(
                        "ERROR", new String[] { "DB_TIMEOUT", "UPSTREAM_503", "CONN_REFUSED", "UNHANDLED" },
                        "WARN", new String[] { "SLOW_DOWNSTREAM", "POOL_PRESSURE", "RETRY_SCHEDULED" });

        private final LogRepository logRepository;
        private final LuceneLogService luceneLogService;
        private final LogWebSocketHandler webSocketHandler;

        private final Random random = new Random(20261003L);

        public DevDataSeeder(
                        LogRepository logRepository,
                        LuceneLogService luceneLogService,
                        LogWebSocketHandler webSocketHandler) {

                this.logRepository = logRepository;
                this.luceneLogService = luceneLogService;
                this.webSocketHandler = webSocketHandler;
        }

        @Override
        public void run(ApplicationArguments args) throws Exception {

                long now = System.currentTimeMillis();

                // Keep the sample dataset inside the dashboard's rolling window.
                // If the newest row has aged past that window (e.g. the dev server
                // was restarted the next day) every chart would show zeros, so the
                // stale rows are cleared and reseeded. While the data is still
                // fresh it is left untouched, keeping restarts fast and idempotent.
                long windowMillis = DASHBOARD_WINDOW_MINUTES * 60_000L;
                long age = logRepository.findTopByOrderByCreatedAtDesc()
                                .map(row -> {
                                        long stored = ageOf(row, now);
                                        // A negative age means the wall clock moved
                                        // backwards (wake from suspend): treat as stale.
                                        return stored < 0 ? Long.MAX_VALUE : stored;
                                })
                                .orElse(Long.MAX_VALUE);

                if (age <= windowMillis) {
                        System.out.println("[DevDataSeeder] Skipped - newest sample row is "
                                        + (age / 60_000L) + " min old (window "
                                        + DASHBOARD_WINDOW_MINUTES + " min).");
                        return;
                }

                boolean reset = age != Long.MAX_VALUE;
                if (reset) {
                        System.out.println("[DevDataSeeder] Refreshing sample data - newest row "
                                        + (age / 60_000L) + " min old; clearing stale rows.");
                        logRepository.deleteAll();
                        for (String tenant : TENANTS) {
                                luceneLogService.resetIndex(tenant);
                        }
                }

                // Spread every tenant's rows across the whole window so charts show
                // a realistic distribution rather than a single spike.
                int ageBiasUpperBound = reset ? SEED_WINDOW_MINUTES : DASHBOARD_WINDOW_MINUTES;

                int total = 0;
                for (String tenant : TENANTS) {
                        total += seedTenant(tenant, now, ageBiasUpperBound);
                }

                System.out.println("[DevDataSeeder] Seeded " + total
                                + " sample logs across " + TENANTS.size() + " tenants.");
        }

        private int seedTenant(String tenant, long now, int ageBiasUpperBound)
                        throws Exception {

                for (int i = 0; i < LOGS_PER_TENANT; i++) {

                        LogRequest request = buildRequest(tenant, now, i, ageBiasUpperBound);
                        LogEntity entity = toEntity(tenant, request);

                        // 1. Relational audit row (Overview "recent activity" + alert engine).
                        logRepository.save(entity);

                        // 2. Lucene document (buffered).
                        luceneLogService.indexLog(request, tenant);

                        // 3. Live Tail broadcast.
                        webSocketHandler.broadcast(toBroadcast(tenant, request, entity));
                }

                // Make the seeded documents searchable in one segment write.
                luceneLogService.commit(tenant);

                return LOGS_PER_TENANT;
        }

        /**
         * Age of a stored audit row in milliseconds, derived from its ISO-8601
         * {@code timestamp} (falling back to {@code createdAt} when absent).
         */
        private static long ageOf(LogEntity row, long now) {

                String timestamp = row.getTimestamp();
                if ((timestamp == null || timestamp.isBlank())
                                && row.getCreatedAt() != null) {
                        return now - row.getCreatedAt()
                                        .toInstant(ZoneOffset.UTC).toEpochMilli();
                }

                return now - LogDocumentMapper.toEpochMillis(timestamp);
        }

        private LogRequest buildRequest(
                        String tenant, long now, int index, int ageBiasUpperBound) {

                String level = LEVELS[random.nextInt(LEVELS.length)];
                String service = SERVICES[random.nextInt(SERVICES.length)];
                String host = HOSTS[random.nextInt(HOSTS.length)];

                String[] messages = MESSAGES.getOrDefault(level, MESSAGES.get("INFO"));
                String message = messages[random.nextInt(messages.length)];

                long responseTime = responseTimeFor(level);

                String[] codes = ERROR_CODES.get(level);
                String errorCode = codes == null ? "" : codes[random.nextInt(codes.length)];

                String traceId = String.format("%016x", random.nextLong());
                // Uniformly spread rows across the dashboard window (0..N minutes ago).
                long ageMinutes = random.nextInt(ageBiasUpperBound);
                String timestamp = Instant.ofEpochMilli(now - ageMinutes * 60_000L).toString();

                return LogRequest.newBuilder()
                                .setTimestamp(timestamp)
                                .setLevel(level)
                                .setService(service)
                                .setMessage(message)
                                .setHost(host)
                                .setTenantId(tenant)
                                .setTraceId(traceId)
                                .setResponseTime(responseTime)
                                .setErrorCode(errorCode)
                                .build();
        }

        private long responseTimeFor(String level) {
                return switch (level) {
                        case "ERROR" -> 800 + random.nextInt(2_200);
                        case "WARN" -> 200 + random.nextInt(600);
                        case "DEBUG" -> 10 + random.nextInt(90);
                        default -> 40 + random.nextInt(260);
                };
        }

        private LogEntity toEntity(String tenant, LogRequest request) {
                return new LogEntity(
                                tenant,
                                request.getTimestamp(),
                                request.getLevel(),
                                request.getService(),
                                request.getMessage(),
                                request.getHost(),
                                request.getTraceId(),
                                request.getErrorCode(),
                                request.getResponseTime());
        }

        private Map<String, Object> toBroadcast(
                        String tenant, LogRequest request, LogEntity entity) {

                Map<String, Object> log = new LinkedHashMap<>();
                log.put("tenant", tenant);
                log.put("timestamp", request.getTimestamp());
                log.put("level", request.getLevel());
                log.put("service", request.getService());
                log.put("message", request.getMessage());
                log.put("host", request.getHost());
                log.put("traceId", request.getTraceId());
                log.put("errorCode", request.getErrorCode());
                log.put("responseTime", request.getResponseTime());
                log.put("createdAt", LocalDateTime.now().toString());
                return log;
        }
}
