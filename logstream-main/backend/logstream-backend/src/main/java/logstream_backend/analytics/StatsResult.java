package logstream_backend.analytics;

import java.util.Map;

/**
 * Dashboard summary counters.
 *
 * @param total   total documents in the tenant index
 * @param byLevel counts keyed by canonical level
 */
public record StatsResult(long total, Map<String, Long> byLevel) {
}
