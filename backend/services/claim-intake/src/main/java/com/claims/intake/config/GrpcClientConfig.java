package com.claims.intake.config;

import com.claims.proto.assessment.v1.AssessmentServiceGrpc;
import com.claims.proto.eligibility.v1.EligibilityServiceGrpc;
import com.claims.proto.policy.v1.PolicyServiceGrpc;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Channels to Policy, Eligibility and Assessment (plaintext gRPC inside the cluster). */
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
    ManagedChannel eligibilityChannel(ChannelFactory f, @Value("${claims.grpc.eligibility-target}") String target) {
        return f.create(target);
    }

    @Bean(destroyMethod = "shutdown")
    ManagedChannel assessmentChannel(ChannelFactory f, @Value("${claims.grpc.assessment-target}") String target) {
        return f.create(target);
    }

    @Bean
    PolicyServiceGrpc.PolicyServiceBlockingStub policyStub(@Qualifier("policyChannel") ManagedChannel ch) {
        return PolicyServiceGrpc.newBlockingStub(ch);
    }

    @Bean
    EligibilityServiceGrpc.EligibilityServiceBlockingStub eligibilityStub(@Qualifier("eligibilityChannel") ManagedChannel ch) {
        return EligibilityServiceGrpc.newBlockingStub(ch);
    }

    @Bean
    AssessmentServiceGrpc.AssessmentServiceBlockingStub assessmentStub(@Qualifier("assessmentChannel") ManagedChannel ch) {
        return AssessmentServiceGrpc.newBlockingStub(ch);
    }
}
