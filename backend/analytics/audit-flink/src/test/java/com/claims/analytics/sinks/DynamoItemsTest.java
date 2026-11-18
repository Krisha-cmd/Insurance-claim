package com.claims.analytics.sinks;

import com.claims.analytics.model.ClaimEvent;
import com.claims.analytics.model.ClaimsOverview;
import com.claims.analytics.model.FraudFlag;
import com.claims.analytics.model.SettlementView;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DynamoItemsTest {

    private static long ts(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    @Test
    void auditItemFollowsTheSingleTableKeysAndGsi() {
        ClaimEvent e = new ClaimEvent();
        e.setEventId("e-77f1");
        e.setEventType("ASSESSMENT_ADJUSTED");
        e.setOccurredAt(ts("2026-11-16T14:05:12.331Z"));
        e.setService("assessment");
        e.setClaimId("c-1042");
        e.setActorId("u-adj-01");
        e.setActorRole("ADJUSTER");
        e.setFromStatus("ASSESSED");
        e.setToStatus("UNDER_REVIEW");
        e.setChangeJson("{\"field\":\"proposedAmount\",\"before\":82000,\"after\":76500}");
        e.setComment("Room rent above plan limit");

        Map<String, AttributeValue> item = DynamoItems.auditItem(e);

        assertThat(item.get("PK").s()).isEqualTo("CLAIM#c-1042");
        assertThat(item.get("SK").s()).isEqualTo("EVENT#2026-11-16T14:05:12.331Z#e-77f1");
        assertThat(item.get("GSI1PK").s()).isEqualTo("TYPE#ASSESSMENT_ADJUSTED");
        assertThat(item.get("GSI1SK").s()).isEqualTo("2026-11-16T14:05:12.331Z");
        assertThat(item.get("type").s()).isEqualTo("AuditEvent");
        assertThat(item.get("actor").m().get("role").s()).isEqualTo("ADJUSTER");
        assertThat(item.get("change").m().get("after").n()).isEqualTo("76500");
        assertThat(item.get("txId").nul()).isTrue();
        assertThat(item).doesNotContainKey("ttl");
    }

    @Test
    void auditSortKeyAlwaysHasMilliseconds() {
        ClaimEvent e = new ClaimEvent();
        e.setEventId("e-1");
        e.setEventType("CLAIM_CREATED");
        e.setClaimId("c-1");
        e.setOccurredAt(ts("2026-11-16T14:05:12Z"));

        assertThat(DynamoItems.auditItem(e).get("SK").s()).isEqualTo("EVENT#2026-11-16T14:05:12.000Z#e-1");
    }

    @Test
    void overviewItemIsKeyedByMetricAndWindowStartWithTtl() {
        ClaimsOverview o = new ClaimsOverview();
        o.setWindowStart(ts("2026-11-16T14:05:00Z"));
        o.setWindowEnd(ts("2026-11-16T14:06:00Z"));
        o.setCountsByStatus(Map.of("APPROVED", 9L));
        o.setApprovalRate(0.82);
        o.setTotalClaimed(new BigDecimal("1245000"));

        Map<String, AttributeValue> item = DynamoItems.overviewItem(o);

        assertThat(item.get("PK").s()).isEqualTo("METRIC#claims_overview#1m");
        assertThat(item.get("SK").s()).isEqualTo("WINDOW#2026-11-16T14:05:00Z");
        assertThat(item.get("type").s()).isEqualTo("WindowAggregate");
        assertThat(item.get("countsByStatus").m().get("APPROVED").n()).isEqualTo("9");
        assertThat(item.get("approvalRate").n()).isEqualTo("0.8200");
        assertThat(item.get("totalClaimedInr").n()).isEqualTo("1245000");
        long sevenDaysAfterEnd = ts("2026-11-23T14:06:00Z") / 1000;
        assertThat(item.get("ttl").n()).isEqualTo(Long.toString(sevenDaysAfterEnd));
    }

    @Test
    void fraudItemIsKeyedByPolicyAndWindowEnd() {
        FraudFlag f = new FraudFlag("POL-2026-00017", ts("2026-11-16T14:00:00Z"), ts("2026-11-16T14:10:00Z"),
                10, List.of("c-1040", "c-1041", "c-1042"));

        Map<String, AttributeValue> item = DynamoItems.fraudItem(f);

        assertThat(item.get("PK").s()).isEqualTo("FRAUD#POL-2026-00017");
        assertThat(item.get("SK").s()).isEqualTo("WINDOW#2026-11-16T14:10:00Z");
        assertThat(item.get("claimCount").n()).isEqualTo("3");
        assertThat(item.get("claimIds").l()).extracting(AttributeValue::s)
                .containsExactly("c-1040", "c-1041", "c-1042");
        assertThat(item).containsKey("ttl");
    }

    @Test
    void settlementItemIsOneStateItemPerTransaction() {
        SettlementView v = new SettlementView("tx-9a21");
        v.setClaimId("c-1042");
        v.setPhase("PRECOMMIT");
        v.getParticipants().put("payment", new SettlementView.Participant("YES", "PREPARED"));
        v.setStartedAt(ts("2026-11-16T14:06:01Z"));
        v.setUpdatedAt(ts("2026-11-16T14:06:02Z"));

        Map<String, AttributeValue> item = DynamoItems.settlementItem(v);

        assertThat(item.get("PK").s()).isEqualTo("TX#tx-9a21");
        assertThat(item.get("SK").s()).isEqualTo("STATE");
        assertThat(item.get("outcome").nul()).isTrue();
        assertThat(item.get("participants").m().get("payment").m().get("vote").s()).isEqualTo("YES");
        assertThat(item.get("startedAt").s()).isEqualTo("2026-11-16T14:06:01.000Z");
        assertThat(item).doesNotContainKey("ttl");
    }
}
