package logstream_backend.controller;

import com.logstream.grpc.LogRequest;

import logstream_backend.entity.LogEntity;
import logstream_backend.repository.LogRepository;
import logstream_backend.service.LuceneLogService;

import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/logs")
public class LogController {

    private final LogRepository logRepository;
    private final LuceneLogService luceneLogService;

    public LogController(
            LogRepository logRepository,
            LuceneLogService luceneLogService) {

        this.logRepository = logRepository;
        this.luceneLogService = luceneLogService;
    }

    /**
     * Total log count.
     */
    @GetMapping("/count")
    public Map<String, Object> getLogCount() {

        return Map.of(
                "totalLogs",
                logRepository.count()
        );
    }

    /**
     * INFO / WARNING / ERROR statistics.
     */
    @GetMapping("/stats")
    public Map<String, Object> getStats() {

        return Map.of(
                "totalLogs",
                logRepository.count(),

                "info",
                logRepository.countByLevel("INFO"),

                "warning",
                logRepository.countByLevel("WARNING"),

                "error",
                logRepository.countByLevel("ERROR")
        );
    }

    /**
     * Get recent logs from PostgreSQL.
     *
     * Example:
     * GET /api/logs?limit=100
     */
    @GetMapping
    public Map<String, Object> getLogs(
            @RequestParam(
                    defaultValue = "100"
            ) int limit) {

        limit = Math.min(
                Math.max(limit, 1),
                500
        );

        List<LogEntity> entities =
                logRepository.findAll();

        /*
         * Latest logs first.
         */
        entities.sort(
                (a, b) ->
                        b.getId()
                                .compareTo(a.getId())
        );

        List<Map<String, Object>> logs =
                entities.stream()
                        .limit(limit)
                        .map(this::toMap)
                        .collect(Collectors.toList());

        return Map.of(
                "logs",
                logs,
                "count",
                logs.size()
        );
    }

    /**
     * Search logs using Lucene.
     *
     * Example:
     *
     * /api/logs/search?q=database
     *
     * /api/logs/search?q=database&level=ERROR
     *
     * /api/logs/search?q=database&level=ERROR&service=billing-api
     */
    @GetMapping("/search")
    public Map<String, Object> searchLogs(

            @RequestParam(
                    required = false,
                    defaultValue = ""
            )
            String q,

            @RequestParam(
                    required = false
            )
            String level,

            @RequestParam(
                    required = false
            )
            String service,

            @RequestParam(
                    defaultValue = "100"
            )
            int limit) {

        try {

            limit = Math.min(
                    Math.max(limit, 1),
                    500
            );

            List<LogRequest> results =
                    luceneLogService.searchLogs(
                            q,
                            level,
                            service,
                            limit
                    );

            List<Map<String, Object>> logs =
                    results.stream()
                            .map(this::grpcToMap)
                            .collect(Collectors.toList());

            return Map.of(
                    "logs",
                    logs,
                    "count",
                    logs.size(),
                    "query",
                    q == null ? "" : q
            );

        } catch (Exception e) {

            e.printStackTrace();

            return Map.of(
                    "logs",
                    List.of(),
                    "count",
                    0,
                    "error",
                    e.getMessage() == null
                            ? "Search failed"
                            : e.getMessage()
            );
        }
    }

    private Map<String, Object> toMap(
            LogEntity entity) {

        Map<String, Object> map =
                new HashMap<>();

        map.put("id", entity.getId());
        map.put("timestamp", entity.getTimestamp());
        map.put("level", entity.getLevel());
        map.put("service", entity.getService());
        map.put("message", entity.getMessage());
        map.put("host", entity.getHost());
        map.put("createdAt", entity.getCreatedAt());

        return map;
    }

    private Map<String, Object> grpcToMap(
            LogRequest log) {

        Map<String, Object> map =
                new HashMap<>();

        map.put(
                "timestamp",
                log.getTimestamp()
        );

        map.put(
                "level",
                log.getLevel()
        );

        map.put(
                "service",
                log.getService()
        );

        map.put(
                "message",
                log.getMessage()
        );

        map.put(
                "host",
                log.getHost()
        );

        return map;
    }
}