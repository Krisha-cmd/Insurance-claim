package com.claims.approval.config;

import com.claims.approval.grpc.GrpcChannelFactory;
import com.claims.approval.service.Halter;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Runs settlements in the background so POST /api/approvals answers 202 straight away. */
    @Bean(name = "settlementExecutor")
    ThreadPoolTaskExecutor settlementExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("settlement-");
        // On shutdown, let running settlements finish their current phase; anything cut off is recovered from the log.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        return executor;
    }

    /** One virtual thread per participant call, so a phase's calls go out in parallel. */
    @Bean(name = "participantCallExecutor", destroyMethod = "shutdownNow")
    ExecutorService participantCallExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /** Chaos crash: halt immediately, like a killed pod. Kubernetes restarts the container. */
    @Bean
    Halter halter() {
        return reason -> {
            org.slf4j.LoggerFactory.getLogger(Halter.class).error("Halting the JVM: {}", reason);
            Runtime.getRuntime().halt(1);
        };
    }

    /** Plaintext gRPC inside the cluster (pod-to-pod over ClusterIP services). */
    @Bean
    GrpcChannelFactory grpcChannelFactory() {
        return target -> Grpc.newChannelBuilder(target, InsecureChannelCredentials.create()).build();
    }
}
