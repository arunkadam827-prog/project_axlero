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
import logstream_backend.search.LogDocumentMapper;
import logstream_backend.search.LogHit;
import logstream_backend.search.SearchResult;
import logstream_backend.tenant.TenantContext;
import logstream_backend.tenant.TenantGrpcInterceptor;
import logstream_backend.websocket.LogWebSocketHandler;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * gRPC entry point for ingestion and ad-hoc search.
 *
 * <p>
 * Tenant resolution order (defence in depth, see
 * {@code docs/MULTI_TENANCY.md} §4):
 * </p>
 * <ol>
 * <li>gRPC metadata {@code tenant-id} bound by
 * {@link TenantGrpcInterceptor};</li>
 * <li>the {@code tenant_id} field on the record itself;</li>
 * <li>the {@code default} tenant.</li>
 * </ol>
 */
@Service
public class LogServiceImpl
                extends LogServiceGrpc.LogServiceImplBase {

        private final LuceneLogService luceneLogService;

        private final LogRepository logRepository;

        private final LogWebSocketHandler logWebSocketHandler;

        private final LogDocumentMapper mapper;

        public LogServiceImpl(
                        LuceneLogService luceneLogService,
                        LogRepository logRepository,
                        LogWebSocketHandler logWebSocketHandler,
                        LogDocumentMapper mapper) {

                this.luceneLogService = luceneLogService;
                this.logRepository = logRepository;
                this.logWebSocketHandler = logWebSocketHandler;
                this.mapper = mapper;
        }

        @Override
        public void sendLog(
                        LogRequest request,
                        StreamObserver<LogResponse> observer) {

                try {

                        String tenant = resolveTenant(request);

                        // 1. PostgreSQL metadata (audit / relational joins).
                        logRepository.save(
                                        new LogEntity(
                                                        tenant,
                                                        request.getTimestamp(),
                                                        request.getLevel(),
                                                        request.getService(),
                                                        request.getMessage(),
                                                        request.getHost(),
                                                        request.getTraceId(),
                                                        request.getErrorCode(),
                                                        request.getResponseTime()));

                        // 2. Lucene (buffered, committed by the scheduled flush).
                        luceneLogService.indexLog(request, tenant);

                        // 3. WebSocket Live Tail (tenant-tagged).
                        broadcastLog(request, tenant);

                        observer.onNext(
                                        LogResponse.newBuilder()
                                                        .setSuccess(true)
                                                        .setAccepted(1)
                                                        .setMessage("Log received successfully")
                                                        .build());

                        observer.onCompleted();

                } catch (Exception e) {

                        e.printStackTrace();

                        observer.onNext(
                                        LogResponse.newBuilder()
                                                        .setSuccess(false)
                                                        .setAccepted(0)
                                                        .setMessage(
                                                                        "Failed to store log: "
                                                                                        + e.getMessage())
                                                        .build());

                        observer.onCompleted();
                }
        }

        @Override
        public void sendLogs(
                        BatchLogRequest request,
                        StreamObserver<LogResponse> observer) {

                try {

                        List<LogRequest> logs = request.getLogsList();

                        // A batch shares one tenant (the metadata-bound / first-record
                        // tenant) so it can be indexed in a single writer operation.
                        String tenant = logs.isEmpty()
                                        ? TenantGrpcInterceptor.tenant()
                                        : resolveTenant(logs.get(0));

                        for (LogRequest log : logs) {
                                logRepository.save(
                                                new LogEntity(
                                                                tenant,
                                                                log.getTimestamp(),
                                                                log.getLevel(),
                                                                log.getService(),
                                                                log.getMessage(),
                                                                log.getHost(),
                                                                log.getTraceId(),
                                                                log.getErrorCode(),
                                                                log.getResponseTime()));

                                broadcastLog(log, tenant);
                        }

                        int accepted = luceneLogService.indexLogs(logs, tenant);

                        observer.onNext(
                                        LogResponse.newBuilder()
                                                        .setSuccess(true)
                                                        .setAccepted(accepted)
                                                        .setMessage(accepted + " logs stored successfully")
                                                        .build());

                        observer.onCompleted();

                } catch (Exception e) {

                        e.printStackTrace();

                        observer.onNext(
                                        LogResponse.newBuilder()
                                                        .setSuccess(false)
                                                        .setAccepted(0)
                                                        .setMessage(
                                                                        "Batch storage failed: "
                                                                                        + e.getMessage())
                                                        .build());

                        observer.onCompleted();
                }
        }

        @Override
        public void searchLogs(
                        SearchRequest request,
                        StreamObserver<SearchResponse> observer) {

                try {

                        // The gRPC thread is not covered by TenantContextFilter, so bind
                        // the tenant carried in metadata to the ThreadLocal for the query.
                        String tenant = TenantGrpcInterceptor.tenant();
                        TenantContext.set(tenant);

                        try {

                                SearchResult result = luceneLogService.search(
                                                request.getQuery(),
                                                request.getLevel(),
                                                request.getService(),
                                                request.getLimit(),
                                                request.getFrom());

                                SearchResponse.Builder builder = SearchResponse.newBuilder()
                                                .setTotalHits(result.totalHits())
                                                .setTookMs(result.tookMs());

                                for (LogHit hit : result.hits()) {
                                        builder.addLogs(mapper.toProto(hit));
                                }

                                observer.onNext(builder.build());
                                observer.onCompleted();

                        } finally {
                                TenantContext.clear();
                        }

                } catch (Exception e) {

                        e.printStackTrace();
                        observer.onError(e);
                }
        }

        /**
         * Resolves the effective tenant: metadata (bound by the interceptor) wins,
         * then the record's own field, then the default tenant.
         */
        private String resolveTenant(LogRequest request) {

                String fromMetadata = TenantGrpcInterceptor.tenant();

                if (fromMetadata != null
                                && !TenantContext.DEFAULT_TENANT.equals(fromMetadata)) {
                        return fromMetadata;
                }

                String fromRecord = request.getTenantId();

                if (fromRecord != null && TenantContext.isValid(fromRecord)) {
                        return TenantContext.normalize(fromRecord);
                }

                return fromMetadata == null
                                ? TenantContext.DEFAULT_TENANT
                                : fromMetadata;
        }

        /**
         * Converts the gRPC log into JSON-friendly data and sends it to all
         * WebSocket clients. The record is tagged with {@code _tenant} so the Live
         * Tail UI can filter to the active tenant.
         */
        private void broadcastLog(
                        LogRequest request,
                        String tenant) {

                Map<String, Object> log = new LinkedHashMap<>();
                log.put("tenant", tenant);
                log.put("timestamp", request.getTimestamp());
                log.put("level", request.getLevel());
                log.put("service", request.getService());
                log.put("message", request.getMessage());
                log.put("host", request.getHost());
                log.put("traceId", request.getTraceId());
                log.put("errorCode", request.getErrorCode());
                log.put("responseTime", request.getResponseTime());
                log.put("createdAt", LocalDateTime.now().toString());

                logWebSocketHandler.broadcast(log);
        }
}
