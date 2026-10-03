package logstream_backend.service;

import logstream_backend.search.LuceneIndexManager;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically commits every tenant's buffered Lucene documents.
 *
 * <p>
 * Ingestion deliberately does not commit per document: buffering many documents
 * per commit is what lifts throughput to the 10k logs/sec target. This flusher
 * bounds the visibility lag (the time between a log being accepted and it being
 * searchable) while keeping the write path fast. See
 * {@code docs/INDEXING_STRATEGY.md} §3.4.
 * </p>
 */
@Component
public class IndexFlushScheduler {

    private final LuceneIndexManager indexManager;

    public IndexFlushScheduler(LuceneIndexManager indexManager) {
        this.indexManager = indexManager;
    }

    /** Flush every second: fresh logs become searchable within ~1s. */
    @Scheduled(fixedRate = 1000)
    public void flush() {
        indexManager.commitAll();
    }
}
