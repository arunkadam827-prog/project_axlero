package logstream_backend.service;

import com.logstream.grpc.LogRequest;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.*;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class LuceneLogService {

    private static final String INDEX_PATH =
            "logs/lucene-index";

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

    public synchronized void indexLog(
            LogRequest request) throws IOException {

        IndexWriterConfig config =
                new IndexWriterConfig(analyzer);

        try (IndexWriter writer =
                     new IndexWriter(directory, config)) {

            Document document = new Document();

            document.add(
                    new StringField(
                            "timestamp",
                            request.getTimestamp(),
                            Field.Store.YES
                    )
            );

            document.add(
                    new StringField(
                            "level",
                            request.getLevel(),
                            Field.Store.YES
                    )
            );

            document.add(
                    new StringField(
                            "service",
                            request.getService(),
                            Field.Store.YES
                    )
            );

            document.add(
                    new TextField(
                            "message",
                            request.getMessage(),
                            Field.Store.YES
                    )
            );

            document.add(
                    new StringField(
                            "host",
                            request.getHost(),
                            Field.Store.YES
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

    public synchronized List<LogRequest> searchLogs(
            String queryText,
            int limit) throws Exception {

        List<LogRequest> results =
                new ArrayList<>();

        if (!DirectoryReader.indexExists(directory)) {
            return results;
        }

        if (limit <= 0) {
            limit = 10;
        }

        try (DirectoryReader reader =
                     DirectoryReader.open(directory)) {

            IndexSearcher searcher =
                    new IndexSearcher(reader);

            Query query;

            if (queryText == null ||
                    queryText.isBlank()) {

                query = new MatchAllDocsQuery();

            } else {

                QueryParser parser =
                        new QueryParser(
                                "message",
                                analyzer
                        );

                query = parser.parse(
                        QueryParser.escape(queryText)
                );
            }

            TopDocs topDocs =
                    searcher.search(query, limit);

            for (ScoreDoc scoreDoc :
                    topDocs.scoreDocs) {

                Document document =
                        searcher.storedFields()
                                .document(scoreDoc.doc);

                LogRequest log =
                        LogRequest.newBuilder()
                                .setTimestamp(
                                        document.get("timestamp")
                                )
                                .setLevel(
                                        document.get("level")
                                )
                                .setService(
                                        document.get("service")
                                )
                                .setMessage(
                                        document.get("message")
                                )
                                .setHost(
                                        document.get("host")
                                )
                                .build();

                results.add(log);
            }
        }

        return results;
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
}