package logstream_backend.service;

import com.logstream.grpc.*;

import io.grpc.stub.StreamObserver;

import logstream_backend.entity.LogEntity;
import logstream_backend.repository.LogRepository;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LogServiceImpl
        extends LogServiceGrpc.LogServiceImplBase {

    private final LuceneLogService luceneLogService;

    private final LogRepository logRepository;

    public LogServiceImpl(
            LuceneLogService luceneLogService,
            LogRepository logRepository) {

        this.luceneLogService = luceneLogService;
        this.logRepository = logRepository;
    }

    @Override
    public void sendLog(
            LogRequest request,
            StreamObserver<LogResponse> observer) {

        try {

            System.out.println();
            System.out.println("========================================");
            System.out.println("LOG RECEIVED");
            System.out.println("========================================");

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

            // PostgreSQL
            LogEntity entity =
                    new LogEntity(
                            request.getTimestamp(),
                            request.getLevel(),
                            request.getService(),
                            request.getMessage(),
                            request.getHost()
                    );

            logRepository.save(entity);

            System.out.println(
                    "POSTGRESQL: Log saved"
            );

            // Lucene
            luceneLogService.indexLog(request);

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
                                log.getLevel(),
                                log.getService(),
                                log.getMessage(),
                                log.getHost()
                        );

                logRepository.save(entity);

                luceneLogService.indexLog(log);

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
}