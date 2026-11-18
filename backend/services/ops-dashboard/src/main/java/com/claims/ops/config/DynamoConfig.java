package com.claims.ops.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.AttributeConverterProvider;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableMetadata;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.document.EnhancedDocument;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;

/**
 * The claims-analytics table through the DynamoDB Enhanced Client. The Flink job writes each item's fields as
 * top-level attributes (with different fields per item type), so the table is mapped as documents with only the
 * keys declared (PK/SK and GSI1PK/GSI1SK); AnalyticsRepository turns each document into an AnalyticsItem.
 * Credentials: the default chain, i.e. the ops-dashboard IRSA role on EKS (no keys).
 */
@Configuration
public class DynamoConfig {

    @Bean(destroyMethod = "close")
    DynamoDbClient dynamoDbClient(@Value("${claims.aws.region}") String region, @Value("${claims.aws.endpoint:}") String endpoint) {
        var b = DynamoDbClient.builder().region(Region.of(region)).credentialsProvider(DefaultCredentialsProvider.create());
        if (endpoint != null && !endpoint.isBlank()) b.endpointOverride(URI.create(endpoint));
        return b.build();
    }

    @Bean
    DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient client) {
        return DynamoDbEnhancedClient.builder().dynamoDbClient(client).build();
    }

    @Bean
    DynamoDbTable<EnhancedDocument> analyticsTable(DynamoDbEnhancedClient enhanced, OpsProperties props) {
        TableSchema<EnhancedDocument> schema = TableSchema.documentSchemaBuilder()
                .addIndexPartitionKey(TableMetadata.primaryIndexName(), "PK", AttributeValueType.S)
                .addIndexSortKey(TableMetadata.primaryIndexName(), "SK", AttributeValueType.S)
                .addIndexPartitionKey(props.gsi1(), "GSI1PK", AttributeValueType.S)
                .addIndexSortKey(props.gsi1(), "GSI1SK", AttributeValueType.S)
                .attributeConverterProviders(AttributeConverterProvider.defaultProvider())
                .build();
        return enhanced.table(props.table(), schema);
    }
}
