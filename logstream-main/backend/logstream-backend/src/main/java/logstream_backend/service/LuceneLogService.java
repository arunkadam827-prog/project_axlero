package logstream_backend.service;

import com.logstream.grpc.LogRequest;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class LuceneLogService {

    private static final String INDEX_PATH = "logs/lucene-index";

    private final StandardAnalyzer analyzer;
    private final Directory directory;

    public LuceneLogService() throws IOException {

        analyzer = new StandardAnalyzer();

        Path indexPath = Path.of(INDEX_PATH);

        directory = FSDirectory.open(indexPath);

        System.out.println(
                "Lucene initialized: "
                        + indexPath.toAbsolutePath()
        );
    }

    /**
     * Add one log to Lucene.
     */
    public synchronized void indexLog(LogRequest request)
            throws IOException {

        IndexWriterConfig config =
                new IndexWriterConfig(analyzer);

        try (IndexWriter writer =
                     new IndexWriter(directory, config)) {

            Document document = new Document();

            document.add(
                    new StringField(
                            "timestamp",
                            request.getTimestamp(),
                            StringField.Store.YES
                    )
            );

            document.add(
                    new StringField(
                            "level",
                            request.getLevel().toUpperCase(),
                            StringField.Store.YES
                    )
            );

            document.add(
                    new StringField(
                            "service",
                            request.getService(),
                            StringField.Store.YES
                    )
            );

            document.add(
                    new TextField(
                            "message",
                            request.getMessage(),
                            TextField.Store.YES
                    )
            );

            document.add(
                    new StringField(
                            "host",
                            request.getHost(),
                            StringField.Store.YES
                    )
            );

            writer.addDocument(document);
            writer.commit();
        }

        System.out.println(
                "LUCENE INDEXED | service="
                        + request.getService()
                        + " | level="
                        + request.getLevel()
                        + " | message="
                        + request.getMessage()
        );
    }

    /**
     * Search logs using message + optional filters.
     */
    public synchronized List<LogRequest> searchLogs(
            String queryText,
            String level,
            String service,
            int limit) throws Exception {

        List<LogRequest> results = new ArrayList<>();

        if (!DirectoryReader.indexExists(directory)) {
            return results;
        }

        if (limit <= 0) {
            limit = 100;
        }

        // Prevent an excessively large browser request.
        limit = Math.min(limit, 500);

        try (DirectoryReader reader =
                     DirectoryReader.open(directory)) {

            IndexSearcher searcher =
                    new IndexSearcher(reader);

            BooleanQuery.Builder builder =
                    new BooleanQuery.Builder();

            /*
             * Message search
             */
            if (queryText != null && !queryText.isBlank()) {

                QueryParser parser =
                        new QueryParser(
                                "message",
                                analyzer
                        );

                Query messageQuery =
                        parser.parse(
                                QueryParser.escape(
                                        queryText.trim()
                                )
                        );

                builder.add(
                        messageQuery,
                        BooleanClause.Occur.MUST
                );

            } else {

                builder.add(
                        new MatchAllDocsQuery(),
                        BooleanClause.Occur.MUST
                );
            }

            /*
             * Level filter
             */
            if (level != null
                    && !level.isBlank()
                    && !level.equalsIgnoreCase("ALL")) {

                builder.add(
                        new TermQuery(
                                new Term(
                                        "level",
                                        level.toUpperCase()
                                )
                        ),
                        BooleanClause.Occur.FILTER
                );
            }

            /*
             * Service filter
             */
            if (service != null
                    && !service.isBlank()
                    && !service.equalsIgnoreCase("ALL")) {

                builder.add(
                        new TermQuery(
                                new Term(
                                        "service",
                                        service
                                )
                        ),
                        BooleanClause.Occur.FILTER
                );
            }

            Query finalQuery = builder.build();

            TopDocs topDocs =
                    searcher.search(finalQuery, limit);

            for (ScoreDoc scoreDoc :
                    topDocs.scoreDocs) {

                Document document =
                        searcher.storedFields()
                                .document(scoreDoc.doc);

                LogRequest log =
                        LogRequest.newBuilder()
                                .setTimestamp(
                                        valueOrEmpty(
                                                document.get("timestamp")
                                        )
                                )
                                .setLevel(
                                        valueOrEmpty(
                                                document.get("level")
                                        )
                                )
                                .setService(
                                        valueOrEmpty(
                                                document.get("service")
                                        )
                                )
                                .setMessage(
                                        valueOrEmpty(
                                                document.get("message")
                                        )
                                )
                                .setHost(
                                        valueOrEmpty(
                                                document.get("host")
                                        )
                                )
                                .build();

                results.add(log);
            }
        }

        return results;
    }

    /**
     * Backward-compatible search method.
     */
    public synchronized List<LogRequest> searchLogs(
            String queryText,
            int limit) throws Exception {

        return searchLogs(
                queryText,
                null,
                null,
                limit
        );
    }

    public synchronized int getTotalLogs()
            throws IOException {

        if (!DirectoryReader.indexExists(directory)) {
            return 0;
        }

        try (DirectoryReader reader =
                     DirectoryReader.open(directory)) {

            return reader.numDocs();
        }
    }

    private String valueOrEmpty(String value) {

        return value == null ? "" : value;
    }
}