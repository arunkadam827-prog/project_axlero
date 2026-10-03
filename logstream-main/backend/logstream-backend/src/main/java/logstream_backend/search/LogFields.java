package logstream_backend.search;

/**
 * Canonical Lucene field names for a log document.
 *
 * <p>
 * Centralising these constants keeps the writer ({@link LuceneIndexManager}),
 * the query builder ({@link LogQueryParser}) and the aggregator
 * ({@code LogAnalyticsService}) in agreement. The mapping rationale is
 * documented in {@code docs/INDEXING_STRATEGY.md} §3.2.
 * </p>
 */
public final class LogFields {

    /** Exact-match tenant scope. Always AND-ed as a FILTER. */
    public static final String TENANT_ID = "tenant_id";

    /** Stored, human-readable ISO-8601 timestamp. */
    public static final String TIMESTAMP = "timestamp";

    /**
     * Epoch millis as a LongPoint + NumericDocValues — used for ranges/histograms.
     */
    public static final String EPOCH_MILLIS = "epoch_millis";

    /** Canonical upper-case level: ERROR / WARN / INFO / DEBUG. */
    public static final String LEVEL = "level";

    public static final String SERVICE = "service";

    public static final String HOST = "host";

    public static final String TRACE_ID = "trace_id";

    public static final String ERROR_CODE = "error_code";

    /** Response duration in millis as an IntPoint — range filterable. */
    public static final String RESPONSE_TIME = "response_time";

    /** Analyzed free text. */
    public static final String MESSAGE = "message";

    private LogFields() {
    }

    /** Normalises a log level to canonical form (e.g. "warn" -> "WARN"). */
    public static String canonicalLevel(String level) {
        if (level == null) {
            return "INFO";
        }
        String value = level.trim().toUpperCase();
        return switch (value) {
            case "ERR" -> "ERROR";
            case "WARNING" -> "WARN";
            case "INFORMATION" -> "INFO";
            case "" -> "INFO";
            default -> value;
        };
    }
}
