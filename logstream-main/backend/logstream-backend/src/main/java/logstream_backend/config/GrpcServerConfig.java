package logstream_backend.config;

import logstream_backend.service.LogServiceImpl;
import logstream_backend.tenant.TenantGrpcInterceptor;

import io.grpc.Server;
import io.grpc.ServerBuilder;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Boots the embedded gRPC server used for high-throughput ingestion.
 *
 * <p>
 * {@link TenantGrpcInterceptor} is registered so every call is bound to a
 * tenant from the {@code tenant-id} metadata key before the service handler
 * runs. See {@code docs/MULTI_TENANCY.md} §4.
 * </p>
 */
@Component
public class GrpcServerConfig {

    private final LogServiceImpl logService;

    @Value("${logstream.grpc.port:9090}")
    private int port;

    @Value("${logstream.grpc.require-tenant:false}")
    private boolean requireTenant;

    private Server server;

    public GrpcServerConfig(LogServiceImpl logService) {
        this.logService = logService;
    }

    @PostConstruct
    public void start() throws IOException {

        server = ServerBuilder
                .forPort(port)
                .addService(io.grpc.ServerInterceptors.intercept(
                        logService, new TenantGrpcInterceptor(requireTenant)))
                .build()
                .start();

        System.out.println("========================================");
        System.out.println("gRPC server started on port " + port);
        System.out.println("========================================");
    }

    @PreDestroy
    public void stop() {

        if (server != null) {
            server.shutdown();
        }
    }
}
