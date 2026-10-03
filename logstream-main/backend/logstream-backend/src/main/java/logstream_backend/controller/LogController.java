package logstream_backend.controller;

import logstream_backend.analytics.FacetBucket;
import logstream_backend.analytics.HistogramResult;
import logstream_backend.analytics.LogAnalyticsService;
import logstream_backend.analytics.StatsResult;
import logstream_backend.entity.LogEntity;
import logstream_backend.repository.LogRepository;
import logstream_backend.search.LogHit;
import logstream_backend.search.SearchResult;
import logstream_backend.service.LuceneLogService;
import logstream_backend.tenant.TenantContext;
import logstream_backend.tenant.TenantContextFilter;

import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tenant-scoped read API used by the dashboard, the Logs explorer and the
 * analytics charts.
 *
 * <p>
 * The tenant is taken from the {@code X-Tenant-Id} header (bound by
 * {@link TenantContextFilter}); it is never taken from a query parameter, so a
 * client cannot ask for another tenant's data.
 * </p>
 */
@RestController
@RequestMapping("/api/logs")
public class LogController {

        private final LogRepository logRepository;
        private final LuceneLogService luceneLogService;
        private final LogAnalyticsService analyticsService;

        public LogController(
                        LogRepository logRepository,
                        LuceneLogService luceneLogService,
                        LogAnalyticsService analyticsService) {

                this.logRepository = logRepository;
                this.luceneLogService = luceneLogService;
                this.analyticsService = analyticsService;
        }

        /** Total indexed (live) documents for the current tenant. */
        @GetMapping("/count")
        public Map<String, Object> getLogCount() throws Exception {

                return Map.of(
                                "totalLogs", luceneLogService.getTotalLogs(),
                                "tenant", TenantContext.get());
        }

        /** Level distribution for the current tenant. */
        @GetMapping("/stats")
        public Map<String, Object> getStats() throws Exception {

                StatsResult stats = analyticsService.stats(TenantContext.get());

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("totalLogs", stats.total());
                response.put("byLevel", stats.byLevel());
                response.put("info", stats.byLevel().getOrDefault("INFO", 0L));
                response.put("warning", stats.byLevel().getOrDefault("WARN", 0L));
                response.put("error", stats.byLevel().getOrDefault("ERROR", 0L));
                response.put("tenant", TenantContext.get());
                return response;
        }

        /**
         * Recent logs from PostgreSQL (newest first). Kept for the Overview "recent
         * activity" table; use {@code /search} for real queries.
         */
        @GetMapping
        public Map<String, Object> getLogs(
                        @RequestParam(defaultValue = "100") int limit) {

                int pageSize = Math.min(Math.max(limit, 1), 500);

                List<LogEntity> entities = logRepository.findAllByTenantId(TenantContext.get());

                entities.sort((a, b) -> b.getId().compareTo(a.getId()));

                List<Map<String, Object>> logs = entities.stream()
                                .limit(pageSize)
                                .map(this::toMap)
                                .toList();

                return Map.of(
                                "logs", logs,
                                "count", logs.size(),
                                "tenant", TenantContext.get());
        }

        /**
         * Full-text / structured search using the LogStream query language.
         *
         * <pre>
         *   /api/logs/search?q=level:ERROR AND response_time > 1000
         *   /api/logs/search?q=database timeout&level=ERROR&limit=50
         * </pre>
         */
        @GetMapping("/search")
        public Map<String, Object> searchLogs(
                        @RequestParam(required = false, defaultValue = "") String q,
                        @RequestParam(required = false) String level,
                        @RequestParam(required = false) String service,
                        @RequestParam(defaultValue = "100") int limit,
                        @RequestParam(defaultValue = "0") int offset) {

                try {

                        SearchResult result = luceneLogService.search(q, level, service, limit, offset);

                        List<Map<String, Object>> logs = result.hits().stream()
                                        .map(this::hitToMap)
                                        .toList();

                        return Map.of(
                                        "logs", logs,
                                        "count", logs.size(),
                                        "totalHits", result.totalHits(),
                                        "tookMs", result.tookMs(),
                                        "query", q == null ? "" : q,
                                        "tenant", TenantContext.get());

                } catch (Exception e) {

                        e.printStackTrace();

                        return Map.of(
                                        "logs", List.of(),
                                        "count", 0,
                                        "totalHits", 0L,
                                        "error", e.getMessage() == null ? "Search failed" : e.getMessage());
                }
        }

        /**
         * Time-series histogram powering the volume / error-rate charts.
         *
         * @param interval bucket size label: {@code 1m}, {@code 5m}, {@code 1h}
         * @param minutes  look-back window in minutes
         */
        @GetMapping("/histogram")
        public HistogramResult histogram(
                        @RequestParam(required = false, defaultValue = "") String q,
                        @RequestParam(required = false, defaultValue = "1h") String interval,
                        @RequestParam(required = false, defaultValue = "60") int minutes) throws Exception {

                long now = System.currentTimeMillis();
                long from = now - (Math.max(minutes, 1) * 60_000L);

                return analyticsService.histogram(
                                TenantContext.get(), q, from, now, intervalToMillis(interval));
        }

        /** Term distribution for a facet dimension (level, service, host). */
        @GetMapping("/facets")
        public List<FacetBucket> facets(
                        @RequestParam(required = false, defaultValue = "") String q,
                        @RequestParam(defaultValue = "service") String field,
                        @RequestParam(defaultValue = "10") int topN,
                        @RequestParam(defaultValue = "60") int minutes) throws Exception {

                long now = System.currentTimeMillis();
                long from = now - (Math.max(minutes, 1) * 60_000L);

                return analyticsService.countByField(
                                TenantContext.get(), q, field, Math.min(Math.max(topN, 1), 50), from, now);
        }

        private long intervalToMillis(String interval) {
                return switch (interval == null ? "" : interval.toLowerCase()) {
                        case "5m" -> 5 * 60_000L;
                        case "15m" -> 15 * 60_000L;
                        case "1h" -> 60 * 60_000L;
                        case "1d" -> 24 * 60 * 60_000L;
                        default -> 60_000L;
                };
        }

        private Map<String, Object> toMap(LogEntity entity) {

                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", entity.getId());
                map.put("tenant", entity.getTenantId());
                map.put("timestamp", entity.getTimestamp());
                map.put("level", entity.getLevel());
                map.put("service", entity.getService());
                map.put("message", entity.getMessage());
                map.put("host", entity.getHost());
                map.put("traceId", entity.getTraceId());
                map.put("errorCode", entity.getErrorCode());
                map.put("responseTime", entity.getResponseTime());
                map.put("createdAt", entity.getCreatedAt());
                return map;
        }

        private Map<String, Object> hitToMap(LogHit hit) {

                Map<String, Object> map = new LinkedHashMap<>();
                map.put("tenant", hit.tenantId());
                map.put("timestamp", hit.timestamp());
                map.put("level", hit.level());
                map.put("service", hit.service());
                map.put("message", hit.message());
                map.put("host", hit.host());
                map.put("traceId", hit.traceId());
                map.put("errorCode", hit.errorCode());
                map.put("responseTime", hit.responseTime());
                return map;
        }
}
