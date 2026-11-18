package com.claims.policy.config;

import com.claims.proto.claim.v1.ClaimServiceGrpc;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The channel to Claim Intake's ClaimService (plaintext gRPC inside the cluster). */
@Configuration
public class GrpcClientConfig {

    /** Creates channels from gRPC targets. Tests replace it with in-process channels. */
    @FunctionalInterface
    public interface ChannelFactory {
        ManagedChannel create(String target);
    }

    @Bean
    ChannelFactory grpcChannelFactory() {
        return target -> Grpc.newChannelBuilder(target, InsecureChannelCredentials.create()).build();
    }

    @Bean(destroyMethod = "shutdown")
    ManagedChannel claimChannel(ChannelFactory f, @Value("${claims.grpc.claim-target}") String target) {
        return f.create(target);
    }

    @Bean
    ClaimServiceGrpc.ClaimServiceBlockingStub claimStub(@Qualifier("claimChannel") ManagedChannel ch) {
        return ClaimServiceGrpc.newBlockingStub(ch);
    }
}
