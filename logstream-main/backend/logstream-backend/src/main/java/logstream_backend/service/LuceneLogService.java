package logstream_backend.service;

import com.logstream.grpc.LogRequest;

import logstream_backend.search.LogDocumentMapper;
import logstream_backend.search.LogHit;
import logstream_backend.search.LogQueryParser;
import logstream_backend.search.LuceneIndexManager;
import logstream_backend.search.SearchResult;
import logstream_backend.tenant.TenantContext;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Tenant-scoped read/write facade over the Lucene index.
 *
 * <p>
 * <strong>Throughput note.</strong> This class used to open (and commit) a
 * brand
 * new {@link IndexWriter} for every single document, which forced a segment
 * refresh per log and capped ingestion at a few hundred logs/sec. It now
 * delegates to {@link LuceneIndexManager}, which keeps one long-lived writer
 * per tenant with an in-memory buffer — the single most important change for
 * reaching the 10,000 logs/sec target. See {@code docs/INDEXING_STRATEGY.md}
 * §3.4.
 * </p>
 *
 * <p>
 * The public method signatures are preserved for backward compatibility with
 * {@code LogController} / {@code LogServiceImpl}, but every path is now scoped
 * to the tenant resolved from {@link TenantContext}.
 * </p>
 */
@Service
public class LuceneLogService {

        private final LuceneIndexManager indexManager;
        private final LogDocumentMapper mapper;
        private final LogQueryParser queryParser;

        public LuceneLogService(
                        LuceneIndexManager indexManager,
                        LogDocumentMapper mapper,
                        LogQueryParser queryParser) {

                this.indexManager = indexManager;
                this.mapper = mapper;
                this.queryParser = queryParser;
        }

        /** Indexes one log for the current tenant (buffered, no commit). */
        public void indexLog(LogRequest request) throws IOException {
                indexLog(request, TenantContext.get());
        }

        /**
         * Indexes one log for an explicit tenant. Does <em>not</em> commit: the
         * caller (or a scheduled flush) controls durability so many documents share
         * a single segment write.
         */
        public void indexLog(LogRequest request, String tenant) throws IOException {

                IndexWriter writer = indexManager.writer(tenant);
                Document document = mapper.toDocument(request, tenant);
                writer.addDocument(document);
        }

        /** Indexes a batch in one writer operation, then commits once. */
        public int indexLogs(List<LogRequest> requests, String tenant) throws IOException {

                if (requests == null || requests.isEmpty()) {
                        return 0;
                }

                IndexWriter writer = indexManager.writer(tenant);
                int accepted = 0;
                for (LogRequest request : requests) {
                        writer.addDocument(mapper.toDocument(request, tenant));
                        accepted++;
                }
                indexManager.commit(tenant);
                return accepted;
        }

        /** Flushes buffered documents for the current tenant. */
        public void commit(String tenant) throws IOException {
                indexManager.commit(tenant);
        }

        /**
         * Drops and recreates a tenant's Lucene index. Used only by the
         * development seeder so aged-out sample data can be replaced by a
         * fresh, in-window dataset.
         */
        public void resetIndex(String tenant) throws IOException {
                indexManager.resetIndex(tenant);
        }

        /** Convenience overload used by the REST/gRPC read paths. */
        public List<LogRequest> searchLogs(String queryText, int limit) throws Exception {
                return searchLogs(queryText, null, null, limit);
        }

        /**
         * Searches the current tenant's index using the LogStream query language,
         * optionally merged with a level/service filter.
         */
        public List<LogRequest> searchLogs(
                        String queryText,
                        String level,
                        String service,
                        int limit) throws Exception {

                return search(queryText, level, service, limit, 0).hits().stream()
                                .map(mapper::toProto)
                                .toList();
        }

        /**
         * Full search entry point returning hits plus metadata.
         *
         * @param queryText free-text or LogStream query language expression
         * @param level     optional exact level filter (merged, may be {@code null})
         * @param service   optional exact service filter (merged, may be {@code null})
         * @param limit     page size (clamped to 1..500)
         * @param offset    zero-based offset for pagination
         */
        public SearchResult search(
                        String queryText,
                        String level,
                        String service,
                        int limit,
                        int offset) throws IOException {

                String tenant = TenantContext.get();

                int pageSize = Math.min(Math.max(limit, 1), 500);
                int from = Math.max(offset, 0);

                DirectoryReader reader = indexManager.reader(tenant);
                if (reader == null) {
                        return new SearchResult(List.of(), 0, 0);
                }

                long started = System.currentTimeMillis();

                IndexSearcher searcher = new IndexSearcher(reader);
                Query query = queryParser.parseWithFilters(queryText, tenant, level, service);

                // Over-fetch to satisfy the offset window without a second round trip.
                int top = Math.min(from + pageSize, 5000);
                TopDocs topDocs = searcher.search(query, top);

                List<LogHit> hits = new ArrayList<>();
                ScoreDoc[] scoreDocs = topDocs.scoreDocs;

                for (int i = from; i < scoreDocs.length && hits.size() < pageSize; i++) {
                        Document document = searcher.storedFields().document(scoreDocs[i].doc);
                        hits.add(mapper.toHit(document));
                }

                long took = System.currentTimeMillis() - started;
                return new SearchResult(hits, topDocs.totalHits.value(), took);
        }

        /** Number of live documents for the current tenant's index. */
        public int getTotalLogs() throws IOException {
                return indexManager.documentCount(TenantContext.get());
        }
}
