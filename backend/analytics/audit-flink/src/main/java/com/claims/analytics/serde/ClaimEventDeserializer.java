package com.claims.analytics.serde;

import com.claims.analytics.model.ClaimEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.serialization.AbstractDeserializationSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Reads a {@code claim-events} JSON message into a {@link ClaimEvent}.
 *
 * <p>Accepts {@code occurredAt} as epoch millis or an ISO-8601 string, and {@code actor} either as an
 * object {@code {"id","role"}} or as flat {@code actorId}/{@code actorRole}. A message that cannot be parsed,
 * or lacks {@code eventId}, {@code eventType} or {@code occurredAt}, is logged and skipped (returning
 * {@code null} makes the Kafka source drop it) so one bad message never fails the job.
 */
public class ClaimEventDeserializer extends AbstractDeserializationSchema<ClaimEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(ClaimEventDeserializer.class);

    private transient ObjectMapper mapper;

    public ClaimEventDeserializer() {
        super(ClaimEvent.class);
    }

    @Override
    public ClaimEvent deserialize(byte[] message) {
        if (message == null) {
            return null;
        }
        try {
            return parse(mapper().readTree(message));
        } catch (IOException | RuntimeException e) {
            LOG.warn("Skipping unreadable claim event: {}", e.getMessage());
            return null;
        }
    }

    private ClaimEvent parse(JsonNode n) throws IOException {
        ClaimEvent e = new ClaimEvent();
        e.setEventId(text(n, "eventId"));
        e.setEventType(text(n, "eventType"));
        Long ts = timestamp(n.get("occurredAt"));
        if (e.getEventId() == null || e.getEventType() == null || ts == null) {
            throw new IllegalArgumentException("eventId, eventType and occurredAt are required");
        }
        e.setOccurredAt(ts);
        e.setService(text(n, "service"));
        e.setClaimId(text(n, "claimId"));
        e.setPolicyNo(text(n, "policyNo"));
        JsonNode actor = n.get("actor");
        if (actor != null && actor.isObject()) {
            e.setActorId(text(actor, "id"));
            e.setActorRole(text(actor, "role"));
            e.setActorName(text(actor, "name"));
        } else {
            e.setActorId(text(n, "actorId"));
            e.setActorRole(text(n, "actorRole"));
            e.setActorName(text(n, "actorName"));
        }
        e.setFromStatus(text(n, "fromStatus"));
        e.setToStatus(text(n, "toStatus"));
        JsonNode amount = n.get("amount");
        if (amount != null && !amount.isNull()) {
            e.setAmount(amount.isNumber() ? amount.decimalValue() : new BigDecimal(amount.asText()));
        }
        e.setComment(text(n, "comment"));
        JsonNode change = n.get("change");
        if (change != null && !change.isNull()) {
            e.setChangeJson(mapper().writeValueAsString(change));
        }
        e.setTxId(text(n, "txId"));
        e.setPhase(text(n, "phase"));
        e.setParticipant(text(n, "participant"));
        e.setVote(text(n, "vote"));
        e.setParticipantState(text(n, "participantState"));
        return e;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Long timestamp(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.asLong();
        }
        return Instant.parse(v.asText()).toEpochMilli();
    }

    private ObjectMapper mapper() {
        if (mapper == null) {
            mapper = new ObjectMapper();
        }
        return mapper;
    }
}
