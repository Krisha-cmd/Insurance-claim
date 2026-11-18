package com.claims.ops.api;

import com.claims.ops.support.OpsTest;
import com.claims.ops.support.SeedItems;
import com.claims.ops.support.Shapes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every read endpoint against LocalStack DynamoDB seeded with the README's sample items (in the Flink job's
 * layout); each response is checked against the shape the frontend mocks return.
 */
@OpsTest
class OpsDashboardIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    static RequestPostProcessor adjuster() {
        return jwt().jwt(j -> j.subject("997bacd0-5b29-4554-b398-52e9f89bfa6c").claim("role", "ADJUSTER").claim("name", "Rohan Mehta"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADJUSTER"));
    }

    static RequestPostProcessor policyholder(UUID userId) {
        return jwt().jwt(j -> j.subject(userId.toString()).claim("role", "POLICYHOLDER").claim("name", "Asha Verma"))
                .authorities(new SimpleGrantedAuthority("ROLE_POLICYHOLDER"));
    }

    private JsonNode body(MockHttpServletRequestBuilder req) throws Exception {
        return json.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void summary() throws Exception {
        JsonNode s = body(get("/api/analytics/summary").with(adjuster()));
        Shapes.assertCovers("summary", s, Set.of());

        assertThat(s.get("windowStart").asText()).isEqualTo(SeedItems.LAST_CLOSED.toString());
        assertThat(s.get("claimsLastMinute").asLong()).isEqualTo(4);
        // Last 30 minutes only: 3+1 approved, 1 rejected (the window 2 hours ago is outside).
        assertThat(s.get("approvalRate").asDouble()).isEqualTo(0.8);
        assertThat(s.get("avgClaimedAmount").asLong()).isEqualTo((360000 + 90000) / 6);
        assertThat(s.get("settlementCommits").asLong()).isEqualTo(3);
        assertThat(s.get("settlementAborts").asLong()).isEqualTo(1);
        assertThat(s.get("lookbackMinutes").asInt()).isEqualTo(30);
    }

    @Test
    void seriesHasOnePointPerMinuteWithGapsFilled() throws Exception {
        JsonNode byStatus = body(get("/api/analytics/series").param("metric", "claims_by_status").param("window", "1m").with(adjuster()));
        Shapes.assertCovers("seriesClaimsByStatus", byStatus, Set.of());
        JsonNode points = byStatus.get("points");
        assertThat(points).hasSize(30);
        JsonNode last = points.get(29);
        assertThat(last.get("windowStart").asText()).isEqualTo(SeedItems.LAST_CLOSED.toString());
        assertThat(last.get("submitted").asLong()).isEqualTo(4);
        assertThat(last.get("approved").asLong()).isEqualTo(3);
        assertThat(last.get("rejected").asLong()).isEqualTo(1);
        assertThat(last.get("settled").asLong()).isEqualTo(2);
        assertThat(points.get(28).get("submitted").asLong()).as("a minute without events").isZero();
        assertThat(points.get(26).get("submitted").asLong()).isEqualTo(2);
        assertThat(last.has("aborts")).isFalse();

        JsonNode aborts = body(get("/api/analytics/series").param("metric", "aborts").with(adjuster()));
        Shapes.assertCovers("seriesAborts", aborts, Set.of());
        assertThat(aborts.get("points").get(29).get("aborts").asLong()).isEqualTo(1);
        assertThat(aborts.get("points").get(29).get("commits").asLong()).isEqualTo(2);

        JsonNode fromParam = body(get("/api/analytics/series").param("metric", "claims_by_status")
                .param("from", SeedItems.LAST_CLOSED.minus(4, ChronoUnit.MINUTES).toString()).with(adjuster()));
        assertThat(fromParam.get("points")).hasSize(5);

        mvc.perform(get("/api/analytics/series").param("metric", "nope").with(adjuster())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/analytics/series").param("window", "5m").with(adjuster())).andExpect(status().isBadRequest());
    }

    @Test
    void fraudFlagsComeFromGsi1_onePerPolicy_newestFirst() throws Exception {
        JsonNode flags = body(get("/api/analytics/fraud-flags").with(adjuster()));
        Shapes.assertCovers("fraudFlags", flags, Set.of());

        assertThat(flags).hasSize(2);
        assertThat(flags.get(0).get("policyNumber").asText()).isEqualTo("POL-2026-00017");
        assertThat(flags.get(0).get("lastClaimAt").asText()).isEqualTo(SeedItems.LAST_CLOSED.toString());
        assertThat(flags.get(0).get("claimCount").asInt()).isEqualTo(3);
        assertThat(flags.get(0).get("claimIds")).hasSize(3);
        assertThat(flags.get(1).get("policyNumber").asText()).isEqualTo("POL-2026-00042");
        assertThat(flags.get(1).get("claimCount").asInt()).isEqualTo(4);
    }

    @Test
    void auditTrail_ascending_inTheFrontendVocabulary() throws Exception {
        JsonNode trail = body(get("/api/audit/claims/{id}", SeedItems.CLAIM).with(adjuster()));
        Shapes.assertCovers("auditTrail", trail, Set.of("before", "after"));

        List<String> types = new ArrayList<>();
        trail.forEach(e -> types.add(e.get("type").asText()));
        assertThat(types).containsExactly("CLAIM_SUBMITTED", "STATUS_CHANGED", "ASSESSMENT_ADJUSTED", "SETTLEMENT_STARTED", "SETTLEMENT_COMMITTED");

        // The fields frontend/src/lib/auditText.js reads for each type.
        JsonNode submitted = trail.get(0);
        assertThat(submitted.get("eventType").asText()).isEqualTo("CLAIM_CREATED");
        assertThat(submitted.get("after").get("claimedAmount").asLong()).isEqualTo(82000);
        assertThat(submitted.get("after").get("hospital").asText()).isEqualTo("Apollo Hospital");
        assertThat(submitted.get("actor").get("name").asText()).isEqualTo("Asha Verma");
        JsonNode status = trail.get(1);
        assertThat(status.get("before").get("status").asText()).isEqualTo("SUBMITTED");
        assertThat(status.get("after").get("status").asText()).isEqualTo("DOCS_PENDING");
        assertThat(status.get("actor").get("role").asText()).isEqualTo("SYSTEM");
        JsonNode adjusted = trail.get(2);
        assertThat(adjusted.get("before").get("proposedAmount").asLong()).isEqualTo(82000);
        assertThat(adjusted.get("after").get("proposedAmount").asLong()).isEqualTo(76500);
        assertThat(adjusted.get("comment").asText()).startsWith("Room rent");
        assertThat(trail.get(3).get("after").get("transactionId").asText()).isEqualTo(SeedItems.TX.toString());
        assertThat(trail.get(3).get("actor").get("name").asText()).as("a service with no name").isEqualTo("approval");
        assertThat(trail.get(4).get("after").get("amount").asLong()).isEqualTo(76500);
    }

    @Test
    void auditTrail_theOwnerMayReadIt_otherPolicyholdersMayNot() throws Exception {
        mvc.perform(get("/api/audit/claims/{id}", SeedItems.CLAIM).with(policyholder(SeedItems.OWNER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5));
        mvc.perform(get("/api/audit/claims/{id}", SeedItems.CLAIM).with(policyholder(UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This claim belongs to another policyholder."));
        mvc.perform(get("/api/audit/claims/{id}", UUID.randomUUID()).with(policyholder(SeedItems.OWNER)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/audit/claims/{id}", UUID.randomUUID()).with(adjuster()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void auditSearch_byTypeAndTime_newestFirst_paged() throws Exception {
        JsonNode all = body(get("/api/audit/events").with(adjuster()));
        Shapes.assertCovers("auditSearch", all, Set.of("before", "after"));
        assertThat(all.get("total").asInt()).isEqualTo(6); // both claims, every type
        assertThat(all.get("items").get(0).get("type").asText()).isEqualTo("SETTLEMENT_COMMITTED");

        JsonNode statusChanges = body(get("/api/audit/events").param("type", "STATUS_CHANGED").with(adjuster()));
        assertThat(statusChanges.get("total").asInt()).isEqualTo(2);
        statusChanges.get("items").forEach(e -> assertThat(e.get("eventType").asText()).isEqualTo("CLAIM_STATUS_CHANGED"));

        JsonNode emittedName = body(get("/api/audit/events").param("type", "ASSESSMENT_ADJUSTED").with(adjuster()));
        assertThat(emittedName.get("total").asInt()).isEqualTo(1);

        String at = SeedItems.firstAuditAt().plusSeconds(1).toString(); // exactly the first status change
        JsonNode window = body(get("/api/audit/events").param("type", "STATUS_CHANGED").param("from", at).param("to", at).with(adjuster()));
        assertThat(window.get("total").asInt()).as("an instant bound without millis still matches").isEqualTo(1);

        JsonNode future = body(get("/api/audit/events").param("from", "2999-01-01").with(adjuster()));
        assertThat(future.get("total").asInt()).isZero();

        JsonNode byActor = body(get("/api/audit/events").param("q", "rohan").with(adjuster()));
        assertThat(byActor.get("total").asInt()).isEqualTo(1);

        JsonNode paged = body(get("/api/audit/events").param("page", "1").param("size", "4").with(adjuster()));
        assertThat(paged.get("items")).hasSize(2);
        assertThat(paged.get("page").asInt()).isEqualTo(1);
    }

    @Test
    void onlyAdjustersSeeAnalyticsAndTheSearch() throws Exception {
        mvc.perform(get("/api/analytics/summary").with(policyholder(SeedItems.OWNER))).andExpect(status().isForbidden());
        mvc.perform(get("/api/audit/events").with(policyholder(SeedItems.OWNER))).andExpect(status().isForbidden());
        mvc.perform(get("/api/ops/chaos").with(policyholder(SeedItems.OWNER))).andExpect(status().isForbidden());
        mvc.perform(get("/api/analytics/summary")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }
}
