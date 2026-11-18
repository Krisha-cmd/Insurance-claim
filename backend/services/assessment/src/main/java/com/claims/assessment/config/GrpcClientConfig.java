package com.claims.assessment.config;

import com.claims.assessment.service.AssessmentCalculator;
import com.claims.proto.claim.v1.ClaimServiceGrpc;
import com.claims.proto.policy.v1.PolicyServiceGrpc;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Channels to Policy's PolicyService and Claim Intake's ClaimService (plaintext gRPC inside the cluster). */
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
    ManagedChannel policyChannel(ChannelFactory f, @Value("${claims.grpc.policy-target}") String target) {
        return f.create(target);
    }

    @Bean(destroyMethod = "shutdown")
    ManagedChannel claimChannel(ChannelFactory f, @Value("${claims.grpc.claim-target}") String target) {
        return f.create(target);
    }

    @Bean
    PolicyServiceGrpc.PolicyServiceBlockingStub policyStub(@Qualifier("policyChannel") ManagedChannel ch) {
        return PolicyServiceGrpc.newBlockingStub(ch);
    }

    @Bean
    ClaimServiceGrpc.ClaimServiceBlockingStub claimStub(@Qualifier("claimChannel") ManagedChannel ch) {
        return ClaimServiceGrpc.newBlockingStub(ch);
    }

    /** The calculator itself has no Spring dependency; this just makes it injectable. */
    @Bean
    AssessmentCalculator assessmentCalculator() {
        return new AssessmentCalculator();
    }
}
