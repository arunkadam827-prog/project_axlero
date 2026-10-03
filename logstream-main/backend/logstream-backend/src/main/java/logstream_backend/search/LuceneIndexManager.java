package logstream_backend.search;

import jakarta.annotation.PreDestroy;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesFacetField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the lifecycle of Lucene resources in a multi-tenant deployment.
 *
 * <p>
 * One {@link IndexWriter} and one cached near-real-time
 * {@link DirectoryReader} exist per tenant. This is the single most important
 * performance decision in LogStream: the original implementation constructed a
 * new {@code IndexWriter} for every single document, which forces a segment
 * open/refresh/commit per log and caps throughput at a few hundred per second.
 * A long-lived writer with an in-memory buffer comfortably exceeds 10,000
 * logs/sec.
 * </p>
 *
 * <p>
 * See {@code docs/INDEXING_STRATEGY.md} §3.4/§3.5 for the tuning rationale.
 * </p>
 */
@Component
public class LuceneIndexManager {

    private final Analyzer analyzer;

    /**
     * Shared facet configuration. Sorted-set dimensions must be declared here
     * (multi-valued) before {@code FacetsConfig.build} is called by the mapper.
     */
    private final FacetsConfig facetsConfig;

    private final Path basePath;

    private final int ramBufferMb;
    private final int maxBufferedDocs;

    /** tenant -> open writer. */
    private final Map<String, IndexWriter> writers = new ConcurrentHashMap<>();

    /** tenant -> cached NRT reader (volatile so refreshes are visible). */
    private final Map<String, DirectoryReader> readers = new ConcurrentHashMap<>();

    /** tenant -> directory handle. */
    private final Map<String, Directory> directories = new ConcurrentHashMap<>();

    public LuceneIndexManager(
            @Value("${logstream.index.path:logs/lucene-index}") String indexPath,
            @Value("${logstream.index.ram-buffer-mb:128}") int ramBufferMb,
            @Value("${logstream.index.max-buffered-docs:50000}") int maxBufferedDocs) {

        this.basePath = Path.of(indexPath);
        this.ramBufferMb = ramBufferMb;
        this.maxBufferedDocs = maxBufferedDocs;
        this.analyzer = new StandardAnalyzer();

        this.facetsConfig = new FacetsConfig();
        // Sorted-set facets are naturally multi-valued; declare them so the
        // config builds the correct docvalues layout. The default index field
        // name ($facets) is used so DefaultSortedSetDocValuesReaderState can
        // read the counts without extra configuration.
        this.facetsConfig.setMultiValued(
                LogFields.LEVEL, false);
        this.facetsConfig.setMultiValued(
                LogFields.SERVICE, true);

        try {
            Files.createDirectories(basePath);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create index base path", e);
        }

        System.out.println(
                "LuceneIndexManager initialised at " + basePath.toAbsolutePath());
    }

    public Analyzer analyzer() {
        return analyzer;
    }

    public FacetsConfig facetsConfig() {
        return facetsConfig;
    }

    /**
     * Returns (creating if needed) the writer for a tenant.
     */
    public IndexWriter writer(String tenant) throws IOException {

        return writers.computeIfAbsent(tenant, t -> {
            try {
                Directory directory = directory(t);

                IndexWriterConfig config = new IndexWriterConfig(analyzer);
                // Prefer RAM-sized flushes over document-count flushes.
                config.setRAMBufferSizeMB(ramBufferMb);
                config.setMaxBufferedDocs(maxBufferedDocs);
                // Append-heavy workload: compound files add write amplification.
                config.setUseCompoundFile(false);
                config.setCommitOnClose(false);
                config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);

                return new IndexWriter(directory, config);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /**
     * Returns a cached reader, refreshing it (NRT) when the index has changed.
     * Cheap to call on every search: {@link DirectoryReader#openIfChanged} is a
     * no-op when nothing was committed.
     */
    public DirectoryReader reader(String tenant) throws IOException {

        DirectoryReader existing = readers.get(tenant);
        DirectoryReader refreshed;

        if (existing == null) {
            if (!DirectoryReader.indexExists(directory(tenant))) {
                return null;
            }
            refreshed = DirectoryReader.open(directory(tenant));
        } else {
            // Refresh the writer's uncommitted state is handled by commit();
            // here we only pick up newly committed segments.
            DirectoryReader changed = DirectoryReader.openIfChanged(existing);
            if (changed == null) {
                return existing;
            }
            refreshed = changed;
        }

        readers.put(tenant, refreshed);
        return refreshed;
    }

    /** Commits buffered documents and makes them visible to NRT readers. */
    public void commit(String tenant) throws IOException {
        IndexWriter writer = writers.get(tenant);
        if (writer != null) {
            writer.commit();
        }
    }

    /** Tenants that currently have an open writer (i.e. have received data). */
    public java.util.Set<String> activeTenants() {
        return java.util.Set.copyOf(writers.keySet());
    }

    /**
     * Commits every tenant's buffered documents. Invoked by a scheduled flush so
     * that ingestion can stay non-blocking while still bounding the visibility
     * lag of new logs. See {@code docs/INDEXING_STRATEGY.md} §3.4.
     */
    public void commitAll() {
        writers.keySet().forEach(tenant -> {
            try {
                commit(tenant);
            } catch (IOException e) {
                System.err.println(
                        "Failed to commit tenant '" + tenant + "': " + e.getMessage());
            }
        });
    }

    /**
     * Deletes every document in a tenant's index and truncates it. Intended
     * for the development seeder only, which refreshes aged-out sample data so
     * the dashboard's rolling time windows stay populated without unbounded
     * growth across developer sessions.
     */
    public synchronized void resetIndex(String tenant) throws IOException {

        DirectoryReader cachedReader = readers.remove(tenant);
        if (cachedReader != null) {
            cachedReader.close();
        }

        IndexWriter existingWriter = writers.remove(tenant);
        if (existingWriter != null) {
            existingWriter.close();
        }

        IndexWriter fresh = writer(tenant);
        fresh.deleteAll();
        fresh.commit();
    }

    /** Publishes a new tenant (idempotent). */
    public void createIndex(String tenant) throws IOException {
        writer(tenant);
    }

    public boolean indexExists(String tenant) throws IOException {
        return DirectoryReader.indexExists(directory(tenant));
    }

    public int documentCount(String tenant) throws IOException {
        DirectoryReader reader = reader(tenant);
        return reader == null ? 0 : reader.numDocs();
    }

    private Directory directory(String tenant) throws IOException {
        return directories.computeIfAbsent(tenant, t -> {
            try {
                Path path = basePath.resolve(t);
                Files.createDirectories(path);
                return FSDirectory.open(path);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    @PreDestroy
    public void close() {

        readers.values().forEach(reader -> {
            try {
                reader.close();
            } catch (IOException ignored) {
            }
        });

        writers.values().forEach(writer -> {
            try {
                writer.close();
            } catch (IOException ignored) {
            }
        });

        directories.values().forEach(directory -> {
            try {
                directory.close();
            } catch (IOException ignored) {
            }
        });

        System.out.println("LuceneIndexManager closed all writers/readers.");
    }
}
