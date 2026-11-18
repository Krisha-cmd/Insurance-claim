package com.claims.analytics.serde;

import com.claims.analytics.model.ClaimEvent;
import com.claims.analytics.model.ClaimsOverview;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimEventDeserializerTest {

    private final ClaimEventDeserializer deserializer = new ClaimEventDeserializer();

    private ClaimEvent read(String json) {
        return deserializer.deserialize(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readsAFullEventWithIsoTimestampAndNestedActor() {
        ClaimEvent e = read("""
                {"eventId":"e-77f1","eventType":"ASSESSMENT_ADJUSTED","occurredAt":"2026-11-16T14:05:12.331Z",
                 "service":"assessment","claimId":"c-1042","policyNo":"POL-1",
                 "actor":{"id":"u-adj-01","role":"ADJUSTER"},"fromStatus":"ASSESSED","toStatus":"UNDER_REVIEW",
                 "amount":76500.50,"change":{"field":"proposedAmount","before":82000,"after":76500},
                 "comment":"capped","txId":null,"unknownField":true}
                """);

        assertThat(e.getEventId()).isEqualTo("e-77f1");
        assertThat(e.getOccurredAt()).isEqualTo(Instant.parse("2026-11-16T14:05:12.331Z").toEpochMilli());
        assertThat(e.getActorId()).isEqualTo("u-adj-01");
        assertThat(e.getActorRole()).isEqualTo("ADJUSTER");
        assertThat(e.getAmount()).isEqualByComparingTo("76500.50");
        assertThat(e.getChangeJson()).isEqualTo("{\"field\":\"proposedAmount\",\"before\":82000,\"after\":76500}");
        assertThat(e.getTxId()).isNull();
    }

    @Test
    void readsEpochMillisAndTxFields() {
        ClaimEvent e = read("""
                {"eventId":"e-1","eventType":"TX_VOTE","occurredAt":1763301962000,"txId":"tx-9a21",
                 "participant":"payment","vote":"YES","participantState":"PREPARED","amount":"1200"}
                """);

        assertThat(e.getOccurredAt()).isEqualTo(1763301962000L);
        assertThat(e.isTxEvent()).isTrue();
        assertThat(e.getParticipant()).isEqualTo("payment");
        assertThat(e.getParticipantState()).isEqualTo("PREPARED");
        assertThat(e.getAmount()).isEqualByComparingTo("1200");
    }

    @Test
    void skipsMalformedOrIncompleteMessages() {
        assertThat(read("not json")).isNull();
        assertThat(read("{\"eventId\":\"e-1\",\"eventType\":\"X\"}")).isNull();
        assertThat(read("{\"eventId\":\"e-1\",\"eventType\":\"X\",\"occurredAt\":\"yesterday\"}")).isNull();
        assertThat(deserializer.deserialize(null)).isNull();
    }

    @Test
    void readsEverySampleEventFromTheReadme() throws Exception {
        java.util.List<String> lines = java.nio.file.Files.readAllLines(java.nio.file.Path.of("sample-events.jsonl"));
        assertThat(lines).isNotEmpty().allSatisfy(line -> assertThat(read(line)).isNotNull());
    }

    @Test
    void serializesResultsWithTheirType() throws Exception {
        ClaimsOverview o = new ClaimsOverview();
        o.setWindowStart(Instant.parse("2026-11-16T14:05:00Z").toEpochMilli());
        o.setWindowEnd(Instant.parse("2026-11-16T14:06:00Z").toEpochMilli());
        o.setCountsByStatus(Map.of("APPROVED", 9L));

        String json = JsonSerializer.toJson(o);

        assertThat(json).startsWith("{\"type\":\"WindowAggregate\"")
                .contains("\"windowStart\":\"2026-11-16T14:05:00Z\"")
                .contains("\"countsByStatus\":{\"APPROVED\":9}");
    }
}
