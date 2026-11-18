package com.claims.ops.repository;

import com.claims.ops.config.OpsProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.document.EnhancedDocument;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Read-only access to claims-analytics. Only Query is used (the IRSA role allows Query and GetItem, not Scan).
 */
@Repository
public class AnalyticsRepository {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final DynamoDbTable<EnhancedDocument> table;
    private final DynamoDbIndex<EnhancedDocument> gsi1;
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    public AnalyticsRepository(DynamoDbTable<EnhancedDocument> analyticsTable, OpsProperties props) {
        this.table = analyticsTable;
        this.gsi1 = analyticsTable.index(props.gsi1());
    }

    /** Items of one partition, by sort key, at most {@code limit}. */
    public List<AnalyticsItem> queryByPk(String pk, boolean newestFirst, int limit) {
        return query(table.query(request(QueryConditional.keyEqualTo(k(pk, null)), newestFirst, limit)).iterator(), limit);
    }

    /** Items of one partition with {@code skFrom ≤ SK}, ascending. */
    public List<AnalyticsItem> queryByPkFrom(String pk, String skFrom, int limit) {
        return query(table.query(request(QueryConditional.sortGreaterThanOrEqualTo(k(pk, skFrom)), false, limit)).iterator(), limit);
    }

    /** GSI1 items with {@code GSI1PK = gsiPk} and {@code from ≤ GSI1SK ≤ to} (either bound may be null). */
    public List<AnalyticsItem> queryGsi1(String gsiPk, String from, String to, boolean newestFirst, int limit) {
        QueryConditional c;
        if (from != null && to != null) c = QueryConditional.sortBetween(k(gsiPk, from), k(gsiPk, to));
        else if (from != null) c = QueryConditional.sortGreaterThanOrEqualTo(k(gsiPk, from));
        else if (to != null) c = QueryConditional.sortLessThanOrEqualTo(k(gsiPk, to));
        else c = QueryConditional.keyEqualTo(k(gsiPk, null));
        return query(gsi1.query(request(c, newestFirst, limit)).iterator(), limit);
    }

    private static QueryEnhancedRequest request(QueryConditional c, boolean newestFirst, int limit) {
        return QueryEnhancedRequest.builder().queryConditional(c).scanIndexForward(!newestFirst)
                .limit(Math.min(limit, 1000)).build();
    }

    private static Key k(String partition, String sort) {
        Key.Builder b = Key.builder().partitionValue(partition);
        if (sort != null) b.sortValue(sort);
        return b.build();
    }

    private List<AnalyticsItem> query(Iterator<Page<EnhancedDocument>> pages, int limit) {
        List<AnalyticsItem> out = new ArrayList<>();
        while (pages.hasNext() && out.size() < limit) {
            for (EnhancedDocument d : pages.next().items()) {
                if (out.size() >= limit) break;
                out.add(toItem(d));
            }
        }
        return out;
    }

    private AnalyticsItem toItem(EnhancedDocument d) {
        try {
            Map<String, Object> data = json.readValue(d.toJson(), MAP);
            return new AnalyticsItem(String.valueOf(data.get("PK")), String.valueOf(data.get("SK")),
                    data.get("type") == null ? null : data.get("type").toString(), data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable analytics item", e);
        }
    }
}
