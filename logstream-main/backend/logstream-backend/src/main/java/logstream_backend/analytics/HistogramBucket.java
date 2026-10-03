package logstream_backend.analytics;

/**
 * One bucket of a time-series histogram (e.g. logs per minute).
 *
 * @param label       display label, e.g. "14:32"
 * @param startMillis bucket start in epoch millis
 * @param count       number of logs in the bucket
 */
public record HistogramBucket(
        String label,
        long startMillis,
        long count) {
}
