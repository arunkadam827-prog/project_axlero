package logstream_backend.analytics;

/**
 * A term-count bucket for a shared dimension (level, service, host...).
 */
public record FacetBucket(
        String key,
        long count) {
}
