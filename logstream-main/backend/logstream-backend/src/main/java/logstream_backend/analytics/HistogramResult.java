package logstream_backend.analytics;

import java.util.List;

/**
 * Time-series histogram response.
 *
 * @param interval interval label, e.g. "1m"
 * @param buckets  buckets ordered chronologically
 * @param total    total matching documents across all buckets
 */
public record HistogramResult(
        String interval,
        List<HistogramBucket> buckets,
        long total) {
}
