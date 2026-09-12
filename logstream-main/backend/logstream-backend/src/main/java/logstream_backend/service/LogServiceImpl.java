package logstream_backend.service;

import com.logstream.grpc.BatchLogRequest;
import com.logstream.grpc.LogRequest;
import com.logstream.grpc.LogResponse;
import com.logstream.grpc.LogServiceGrpc;
import com.logstream.grpc.SearchRequest;
import com.logstream.grpc.SearchResponse;

import io.grpc.stub.StreamObserver;

import logstream_backend.entity.LogEntity;
import logstream_backend.repository.LogRepository;
import logstream_backend.websocket.LogWebSocketHandler;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class LogServiceImpl
        extends LogServiceGrpc.LogServiceImplBase {

    private final LuceneLogService luceneLogService;

    private final LogRepository logRepository;

    private final LogWebSocketHandler
            logWebSocketHandler;

    public LogServiceImpl(
            LuceneLogService luceneLogService,
            LogRepository logRepository,
            LogWebSocketHandler logWebSocketHandler) {

        this.luceneLogService =
                luceneLogService;

        this.logRepository =
                logRepository;

        this.logWebSocketHandler =
                logWebSocketHandler;
    }

    @Override
    public void sendLog(
            LogRequest request,
            StreamObserver<LogResponse> observer) {

        try {

            printLog(request);

            /*
             * 1. PostgreSQL
             */
            LogEntity entity =
                    new LogEntity(
                            request.getTimestamp(),
                            request.getLevel()
                                    .toUpperCase(),
                            request.getService(),
                            request.getMessage(),
                            request.getHost()
                    );

            logRepository.save(entity);

            System.out.println(
                    "POSTGRESQL: Log saved"
            );

            /*
             * 2. Lucene
             */
            luceneLogService.indexLog(request);

            /*
             * 3. WebSocket
             *
             * This sends the newly received
             * log immediately to React Live Tail.
             */
            broadcastLog(request);

            long totalLogs =
                    logRepository.count();

            LogResponse response =
                    LogResponse.newBuilder()
                            .setSuccess(true)
                            .setMessage(
                                    "Log received successfully. "
                                            + "Total logs: "
                                            + totalLogs
                            )
                            .build();

            observer.onNext(response);
            observer.onCompleted();

        } catch (Exception e) {

            e.printStackTrace();

            observer.onNext(
                    LogResponse.newBuilder()
                            .setSuccess(false)
                            .setMessage(
                                    "Failed to store log: "
                                            + e.getMessage()
                            )
                            .build()
            );

            observer.onCompleted();
        }
    }

    @Override
    public void sendLogs(
            BatchLogRequest request,
            StreamObserver<LogResponse> observer) {

        try {

            int count = 0;

            for (LogRequest log :
                    request.getLogsList()) {

                LogEntity entity =
                        new LogEntity(
                                log.getTimestamp(),
                                log.getLevel()
                                        .toUpperCase(),
                                log.getService(),
                                log.getMessage(),
                                log.getHost()
                        );

                logRepository.save(entity);

                luceneLogService.indexLog(log);

                /*
                 * Send every log to Live Tail.
                 */
                broadcastLog(log);

                count++;
            }

            long totalLogs =
                    logRepository.count();

            observer.onNext(
                    LogResponse.newBuilder()
                            .setSuccess(true)
                            .setMessage(
                                    count
                                            + " logs stored successfully. "
                                            + "Total logs: "
                                            + totalLogs
                            )
                            .build()
            );

            observer.onCompleted();

        } catch (Exception e) {

            e.printStackTrace();

            observer.onNext(
                    LogResponse.newBuilder()
                            .setSuccess(false)
                            .setMessage(
                                    "Batch storage failed: "
                                            + e.getMessage()
                            )
                            .build()
            );

            observer.onCompleted();
        }
    }

    @Override
    public void searchLogs(
            SearchRequest request,
            StreamObserver<SearchResponse> observer) {

        try {

            List<LogRequest> logs =
                    luceneLogService.searchLogs(
                            request.getQuery(),
                            request.getLimit()
                    );

            observer.onNext(
                    SearchResponse.newBuilder()
                            .addAllLogs(logs)
                            .build()
            );

            observer.onCompleted();

        } catch (Exception e) {

            e.printStackTrace();

            observer.onError(e);
        }
    }

    /**
     * Convert the gRPC log into JSON-friendly
     * data and send it to all WebSocket clients.
     */
    private void broadcastLog(
            LogRequest request) {

        Map<String, Object> log =
                Map.of(
                        "timestamp",
                        request.getTimestamp(),

                        "level",
                        request.getLevel(),

                        "service",
                        request.getService(),

                        "message",
                        request.getMessage(),

                        "host",
                        request.getHost()
                );

        logWebSocketHandler.broadcast(log);
    }

    private void printLog(
            LogRequest request) {

        System.out.println();
        System.out.println(
                "========================================"
        );
        System.out.println("LOG RECEIVED");
        System.out.println(
                "========================================"
        );

        System.out.println(
                "Timestamp : "
                        + request.getTimestamp()
        );

        System.out.println(
                "Level     : "
                        + request.getLevel()
        );

        System.out.println(
                "Service   : "
                        + request.getService()
        );

        System.out.println(
                "Message   : "
                        + request.getMessage()
        );

        System.out.println(
                "Host      : "
                        + request.getHost()
        );
    }
}