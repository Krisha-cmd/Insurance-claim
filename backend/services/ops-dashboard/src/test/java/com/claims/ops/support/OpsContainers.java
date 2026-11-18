package com.claims.ops.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * LocalStack DynamoDB with the claims-analytics table exactly as Terraform's dynamodb module creates it
 * (PK/SK + GSI1 on GSI1PK/GSI1SK, projection ALL), seeded with {@link SeedItems}; and two WireMock servers
 * standing in for Approval's and Payment's /internal/chaos.
 */
public interface OpsContainers {

    String TABLE = "claims-test-analytics";

    LocalStackContainer LOCALSTACK = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8.1"))
            .withServices(LocalStackContainer.Service.DYNAMODB);

    WireMockServer APPROVAL = Env.wiremock();
    WireMockServer PAYMENT = Env.wiremock();
    DynamoDbClient DYNAMO = Env.start();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("claims.aws.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        r.add("claims.aws.region", LOCALSTACK::getRegion);
        r.add("claims.ops.table", () -> TABLE);
        r.add("claims.ops.approval-url", APPROVAL::baseUrl);
        r.add("claims.ops.payment-url", PAYMENT::baseUrl);
    }

    final class Env {
        private Env() {
        }

        static WireMockServer wiremock() {
            WireMockServer s = new WireMockServer(options().dynamicPort());
            s.start();
            return s;
        }

        static DynamoDbClient start() {
            LOCALSTACK.start();
            System.setProperty("aws.accessKeyId", LOCALSTACK.getAccessKey());
            System.setProperty("aws.secretAccessKey", LOCALSTACK.getSecretKey());
            DynamoDbClient c = DynamoDbClient.builder().endpointOverride(LOCALSTACK.getEndpoint())
                    .region(Region.of(LOCALSTACK.getRegion()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                    .build();
            c.createTable(b -> b.tableName(TABLE).billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(attr("PK"), attr("SK"), attr("GSI1PK"), attr("GSI1SK"))
                    .keySchema(key("PK", KeyType.HASH), key("SK", KeyType.RANGE))
                    .globalSecondaryIndexes(GlobalSecondaryIndex.builder().indexName("GSI1")
                            .keySchema(key("GSI1PK", KeyType.HASH), key("GSI1SK", KeyType.RANGE))
                            .projection(Projection.builder().projectionType(ProjectionType.ALL).build()).build()));
            SeedItems.writeAll(c, TABLE);
            return c;
        }

        private static AttributeDefinition attr(String name) {
            return AttributeDefinition.builder().attributeName(name).attributeType(ScalarAttributeType.S).build();
        }

        private static KeySchemaElement key(String name, KeyType type) {
            return KeySchemaElement.builder().attributeName(name).keyType(type).build();
        }
    }
}
