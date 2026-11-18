package com.claims.analytics.sinks;

import com.claims.analytics.model.ClaimEvent;
import com.claims.analytics.model.ClaimsOverview;
import com.claims.analytics.model.FraudFlag;
import com.claims.analytics.model.SettlementView;
import com.claims.analytics.model.Timestamps;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.connector.sink2.SinkWriter;
import org.apache.flink.connector.base.sink.writer.ElementConverter;
import org.apache.flink.connector.dynamodb.sink.DynamoDbWriteRequest;
import org.apache.flink.connector.dynamodb.sink.DynamoDbWriteRequestType;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@code claims-analytics} single-table items (see the "NoSQL DB" section of the root README):
 *
 * <pre>
 * Audit Event       CLAIM#&lt;claimId&gt;                EVENT#&lt;isoTs&gt;#&lt;eventId&gt;   + GSI1PK=TYPE#&lt;eventType&gt;, GSI1SK=&lt;isoTs&gt;
 * Window Aggregate  METRIC#claims_overview#1m      WINDOW#&lt;windowStart&gt;         + ttl
 * Fraud Flag        FRAUD#&lt;policyNo&gt;               WINDOW#&lt;windowEnd&gt;           + ttl
 * Settlement View   TX#&lt;txId&gt;                      STATE
 * </pre>
 *
 * Every item is a PUT on a deterministic key, so a replay after a failure overwrites the same item: this is what
 * makes the at-least-once sinks safe.
 */
public final class DynamoItems {

    /** Retention of window aggregates and fraud flags (DynamoDB TTL). */
    public static final Duration ANALYTICS_RETENTION = Duration.ofDays(7);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DynamoItems() {
    }

    // ------------------------------------------------------------------ item builders

    public static Map<String, AttributeValue> auditItem(ClaimEvent e) {
        String isoTs = Timestamps.isoMillis(e.getOccurredAt());
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("type", "AuditEvent");
        doc.put("eventId", e.getEventId());
        doc.put("eventType", e.getEventType());
        doc.put("occurredAt", isoTs);
        doc.put("service", e.getService());
        doc.put("claimId", e.getClaimId());
        doc.put("policyNo", e.getPolicyNo());
        if (e.getActorId() != null || e.getActorRole() != null) {
            Map<String, Object> actor = new LinkedHashMap<>();
            actor.put("id", e.getActorId());
            actor.put("role", e.getActorRole());
            doc.put("actor", actor);
        } else {
            doc.put("actor", null);
        }
        doc.put("fromStatus", e.getFromStatus());
        doc.put("toStatus", e.getToStatus());
        doc.put("amount", e.getAmount());
        doc.put("change", parseJson(e.getChangeJson()));
        doc.put("comment", e.getComment());
        doc.put("txId", e.getTxId());
        doc.put("phase", e.getPhase());
        if (e.getParticipant() != null) {
            doc.put("participant", e.getParticipant());
            doc.put("vote", e.getVote());
            doc.put("participantState", e.getParticipantState());
        }
        Map<String, AttributeValue> item = keys("CLAIM#" + e.getClaimId(), "EVENT#" + isoTs + "#" + e.getEventId());
        item.putAll(attributes(doc));
        item.put("GSI1PK", s("TYPE#" + e.getEventType()));
        item.put("GSI1SK", s(isoTs));
        return item;
    }

    public static Map<String, AttributeValue> overviewItem(ClaimsOverview o) {
        Map<String, AttributeValue> item = keys(
                "METRIC#" + ClaimsOverview.METRIC_NAME + "#" + ClaimsOverview.WINDOW_LABEL,
                "WINDOW#" + Timestamps.isoSeconds(o.getWindowStart()));
        item.putAll(attributes(o.toDocument()));
        item.put("ttl", ttl(o.getWindowEnd()));
        return item;
    }

    public static Map<String, AttributeValue> fraudItem(FraudFlag f) {
        Map<String, AttributeValue> item = keys(
                "FRAUD#" + f.getPolicyNo(), "WINDOW#" + Timestamps.isoSeconds(f.getWindowEnd()));
        item.putAll(attributes(f.toDocument()));
        item.put("ttl", ttl(f.getWindowEnd()));
        return item;
    }

    public static Map<String, AttributeValue> settlementItem(SettlementView v) {
        Map<String, AttributeValue> item = keys("TX#" + v.getTxId(), "STATE");
        item.putAll(attributes(v.toDocument()));
        return item;
    }

    // ------------------------------------------------------------------ Flink element converters

    public static ElementConverter<ClaimEvent, DynamoDbWriteRequest> auditConverter() {
        return new PutConverter<>() {
            @Override
            Map<String, AttributeValue> item(ClaimEvent e) {
                return auditItem(e);
            }
        };
    }

    public static ElementConverter<ClaimsOverview, DynamoDbWriteRequest> overviewConverter() {
        return new PutConverter<>() {
            @Override
            Map<String, AttributeValue> item(ClaimsOverview o) {
                return overviewItem(o);
            }
        };
    }

    public static ElementConverter<FraudFlag, DynamoDbWriteRequest> fraudConverter() {
        return new PutConverter<>() {
            @Override
            Map<String, AttributeValue> item(FraudFlag f) {
                return fraudItem(f);
            }
        };
    }

    public static ElementConverter<SettlementView, DynamoDbWriteRequest> settlementConverter() {
        return new PutConverter<>() {
            @Override
            Map<String, AttributeValue> item(SettlementView v) {
                return settlementItem(v);
            }
        };
    }

    /** Wraps an item builder as a PUT request. Abstract class (not a lambda) so it is reliably serializable. */
    abstract static class PutConverter<T> implements ElementConverter<T, DynamoDbWriteRequest> {
        abstract Map<String, AttributeValue> item(T element);

        @Override
        public DynamoDbWriteRequest apply(T element, SinkWriter.Context context) {
            return DynamoDbWriteRequest.builder()
                    .setType(DynamoDbWriteRequestType.PUT)
                    .setItem(item(element))
                    .build();
        }
    }

    // ------------------------------------------------------------------ attribute mapping

    private static Map<String, AttributeValue> keys(String pk, String sk) {
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("PK", s(pk));
        item.put("SK", s(sk));
        return item;
    }

    private static Map<String, AttributeValue> attributes(Map<String, Object> doc) {
        Map<String, AttributeValue> out = new LinkedHashMap<>();
        doc.forEach((k, v) -> out.put(k, toAttribute(v)));
        return out;
    }

    /** Maps a JSON-compatible value to a DynamoDB attribute (numbers stay exact as N strings). */
    static AttributeValue toAttribute(Object v) {
        if (v == null) {
            return AttributeValue.builder().nul(true).build();
        }
        if (v instanceof String str) {
            return s(str);
        }
        if (v instanceof BigDecimal bd) {
            return AttributeValue.builder().n(bd.toPlainString()).build();
        }
        if (v instanceof Number num) {
            return AttributeValue.builder().n(num.toString()).build();
        }
        if (v instanceof Boolean b) {
            return AttributeValue.builder().bool(b).build();
        }
        if (v instanceof byte[] bytes) {
            return AttributeValue.builder().b(SdkBytes.fromByteArray(bytes)).build();
        }
        if (v instanceof Map<?, ?> map) {
            Map<String, AttributeValue> m = new LinkedHashMap<>();
            map.forEach((k, val) -> m.put(String.valueOf(k), toAttribute(val)));
            return AttributeValue.builder().m(m).build();
        }
        if (v instanceof Iterable<?> it) {
            List<AttributeValue> l = new ArrayList<>();
            it.forEach(val -> l.add(toAttribute(val)));
            return AttributeValue.builder().l(l).build();
        }
        return s(v.toString());
    }

    private static AttributeValue s(String v) {
        return AttributeValue.builder().s(v).build();
    }

    private static AttributeValue ttl(long windowEndMillis) {
        long epochSeconds = (windowEndMillis + ANALYTICS_RETENTION.toMillis()) / 1000;
        return AttributeValue.builder().n(Long.toString(epochSeconds)).build();
    }

    private static Object parseJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, new TypeReference<Object>() {
            });
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
