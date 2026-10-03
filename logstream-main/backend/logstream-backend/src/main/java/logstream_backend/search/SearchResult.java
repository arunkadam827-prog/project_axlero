package logstream_backend.search;

import java.util.List;

/**
 * Result of a search: the page of hits plus global metadata.
 *
 * @param hits      the page of matching documents
 * @param totalHits total matching documents (not limited by the page size)
 * @param tookMs    server-side execution time in milliseconds
 */
public record SearchResult(
        List<LogHit> hits,
        long totalHits,
        long tookMs) {
}
