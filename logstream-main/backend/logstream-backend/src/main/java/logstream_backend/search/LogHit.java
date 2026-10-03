package logstream_backend.search;

/**
 * A single search result. Immutable DTO shared by the REST and gRPC layers.
 */
public record LogHit(
        String tenantId,
        String timestamp,
        long epochMillis,
        String level,
        String service,
        String message,
        String host,
        String traceId,
        String errorCode,
        int responseTime) {
}
