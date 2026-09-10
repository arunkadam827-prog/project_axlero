package logstream_backend.config;

import io.grpc.BindableService;
import logstream_backend.service.LogServiceImpl;

import io.grpc.Server;
import io.grpc.ServerBuilder;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class GrpcServerConfig {

    private final LogServiceImpl logService;

    private Server server;

    public GrpcServerConfig(LogServiceImpl logService) {
        this.logService = logService;
    }

    @PostConstruct
    public void start() throws IOException {

        server = ServerBuilder
                .forPort(9090)
                .addService( logService)
                .build()
                .start();

        System.out.println("========================================");
        System.out.println("gRPC server started on port 9090");
        System.out.println("========================================");
    }

    @PreDestroy
    public void stop() {

        if (server != null) {
            server.shutdown();
        }
    }
}