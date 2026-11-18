package com.claims.intake.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.localstack.LocalStackContainer.Service;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Postgres 16 and LocalStack (S3 + SQS), started once per test run. Imported with @ImportTestcontainers,
 * so every @IntakeTest class shares one Spring context.
 */
public interface IntakeContainers {

    String BUCKET = "claims-test-documents";
    String RESULTS_QUEUE = "doc-verification-results";

    @ServiceConnection
    PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    LocalStackContainer LOCALSTACK = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8.1"))
            .withServices(Service.S3, Service.SQS);

    Resources RESOURCES = Resources.create();

    @DynamicPropertySource
    static void awsProperties(DynamicPropertyRegistry r) {
        r.add("claims.aws.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        r.add("claims.aws.region", LOCALSTACK::getRegion);
        r.add("claims.intake.documents.bucket", () -> BUCKET);
        r.add("claims.intake.results.queue-url", () -> RESOURCES.queueUrl());
    }

    /** Starts the containers and creates the bucket and the results queue. */
    record Resources(String queueUrl, S3Client s3, SqsClient sqs) {

        static Resources create() {
            POSTGRES.start();
            LOCALSTACK.start();
            // The service uses the default AWS credential chain; LocalStack accepts any key.
            System.setProperty("aws.accessKeyId", LOCALSTACK.getAccessKey());
            System.setProperty("aws.secretAccessKey", LOCALSTACK.getSecretKey());
            var creds = StaticCredentialsProvider.create(AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey()));
            S3Client s3 = S3Client.builder().endpointOverride(LOCALSTACK.getEndpoint()).region(Region.of(LOCALSTACK.getRegion()))
                    .credentialsProvider(creds).forcePathStyle(true).build();
            SqsClient sqs = SqsClient.builder().endpointOverride(LOCALSTACK.getEndpoint()).region(Region.of(LOCALSTACK.getRegion()))
                    .credentialsProvider(creds).build();
            s3.createBucket(b -> b.bucket(BUCKET));
            String url = sqs.createQueue(b -> b.queueName(RESULTS_QUEUE)
                    .attributes(java.util.Map.of(software.amazon.awssdk.services.sqs.model.QueueAttributeName.VISIBILITY_TIMEOUT, "2"))).queueUrl();
            return new Resources(url, s3, sqs);
        }
    }
}
