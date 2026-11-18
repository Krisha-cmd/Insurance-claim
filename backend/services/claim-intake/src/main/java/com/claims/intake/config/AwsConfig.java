package com.claims.intake.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;

/**
 * AWS clients. On EKS the credentials come from the claim-intake IRSA role (web identity) through the
 * default provider chain. {@code claims.aws.endpoint} is only set for LocalStack.
 */
@Configuration
public class AwsConfig {

    private final Region region;
    private final String endpoint;

    public AwsConfig(@Value("${claims.aws.region}") String region, @Value("${claims.aws.endpoint:}") String endpoint) {
        this.region = Region.of(region);
        this.endpoint = endpoint;
    }

    @Bean
    AwsCredentialsProvider awsCredentialsProvider() {
        return DefaultCredentialsProvider.create();
    }

    @Bean(destroyMethod = "close")
    S3Presigner s3Presigner(AwsCredentialsProvider credentials) {
        S3Presigner.Builder b = S3Presigner.builder().region(region).credentialsProvider(credentials)
                // Path-style for LocalStack; virtual-hosted (the default) on AWS.
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(hasEndpoint()).build());
        if (hasEndpoint()) b.endpointOverride(URI.create(endpoint));
        return b.build();
    }

    @Bean(destroyMethod = "close")
    SqsClient sqsClient(AwsCredentialsProvider credentials) {
        var b = SqsClient.builder().region(region).credentialsProvider(credentials);
        if (hasEndpoint()) b.endpointOverride(URI.create(endpoint));
        return b.build();
    }

    private boolean hasEndpoint() {
        return endpoint != null && !endpoint.isBlank();
    }
}
