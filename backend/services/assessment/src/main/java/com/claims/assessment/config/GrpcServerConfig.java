package com.claims.assessment.config;

import io.grpc.BindableService;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Serves every {@link BindableService} bean (AssessmentService) on
 * {@code claims.grpc.server.port} (9090). Pods are probed over HTTP (actuator). Tests can serve in-process
 * instead by setting {@code claims.grpc.server.in-process-name}.
 */
@Component
public class GrpcServerConfig implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerConfig.class);

    private final List<BindableService> services;
    private final int port;
    private final String inProcessName;
    private volatile Server server;

    public GrpcServerConfig(List<BindableService> services,
                            @Value("${claims.grpc.server.port}") int port,
                            @Value("${claims.grpc.server.in-process-name:}") String inProcessName) {
        this.services = services;
        this.port = port;
        this.inProcessName = inProcessName;
    }

    @Override
    public void start() {
        ServerBuilder<?> builder;
        if (!inProcessName.isBlank()) {
            builder = inProcessBuilder(inProcessName);
        } else {
            builder = Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create());
        }
        services.forEach(builder::addService);
        try {
            server = builder.build().start();
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the gRPC server on port " + port, e);
        }
        log.info("gRPC server started ({}) with {}", inProcessName.isBlank() ? "port " + port : "in-process " + inProcessName,
                services.stream().map(s -> s.bindService().getServiceDescriptor().getName()).toList());
    }

    /** grpc-inprocess is only on the test classpath, so it is loaded reflectively. */
    private static ServerBuilder<?> inProcessBuilder(String name) {
        try {
            return (ServerBuilder<?>) Class.forName("io.grpc.inprocess.InProcessServerBuilder")
                    .getMethod("forName", String.class).invoke(null, name);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("In-process gRPC needs io.grpc:grpc-inprocess on the classpath", e);
        }
    }

    @Override
    public void stop() {
        Server s = server;
        if (s == null) return;
        s.shutdown();
        try {
            if (!s.awaitTermination(5, TimeUnit.SECONDS)) s.shutdownNow();
        } catch (InterruptedException e) {
            s.shutdownNow();
            Thread.currentThread().interrupt();
        }
        server = null;
    }

    @Override
    public boolean isRunning() {
        return server != null;
    }
}
