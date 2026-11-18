package com.claims.intake.e2e;

import com.claims.common.event.ClaimEvent;
import com.claims.intake.domain.ClaimStatus;
import com.claims.intake.repository.ClaimStatusHistoryRepository;
import com.claims.intake.repository.EligibilityCheckRepository;
import com.claims.intake.support.FakeChecks;
import com.claims.intake.support.IntakeContainers;
import com.claims.intake.support.IntakeTestBase;
import com.claims.intake.support.IntakeTestConfig;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole intake path against Postgres + LocalStack S3/SQS, with Eligibility and Assessment as in-process
 * gRPC fakes: create → 4 upload URLs → real PUTs to S3 → 4 VERIFIED results on SQS → UNDER_REVIEW.
 */
class ClaimLifecycleE2ETest extends IntakeTestBase {

    private static final List<String> REQUIRED = List.of("DISCHARGE_SUMMARY", "FINAL_BILL", "PRESCRIPTION", "ID_PROOF");
    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired ClaimStatusHistoryRepository history;
    @Autowired EligibilityCheckRepository eligibilityChecks;

    private UUID createClaim(Holder h) throws Exception {
        String body = mvc.perform(post("/api/claims").with(as(h)).contentType(MediaType.APPLICATION_JSON)
                        .content(claimJson(h, "82000")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DOCS_PENDING"))
                .andExpect(jsonPath("$.claimNo").value(org.hamcrest.Matchers.matchesPattern("CLM-\\d{4}-\\d{6}")))
                .andExpect(jsonPath("$.policyNumber").value(h.policyNo()))
                .andExpect(jsonPath("$.patient.name").value("Asha Verma"))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("id").asText());
    }

    /** Asks for an upload URL and PUTs a small PDF to it, like the browser does. Returns docType → (documentId, key). */
    private Map<String, String[]> uploadAll(Holder h, UUID claimId) throws Exception {
        Map<String, String[]> docs = new LinkedHashMap<>();
        for (String type : REQUIRED) {
            String res = mvc.perform(post("/api/claims/{id}/documents/upload-url", claimId).with(as(h))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fileName\":\"" + type.toLowerCase() + " scan (1).pdf\",\"contentType\":\"application/pdf\",\"docType\":\"" + type + "\",\"size\":1234}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.method").value("PUT"))
                    .andExpect(jsonPath("$.headers['Content-Type']").value("application/pdf"))
                    .andReturn().getResponse().getContentAsString();
            JsonNode n = json.readTree(res);
            String key = n.get("key").asText();
            assertThat(key).matches("claims/" + claimId + "/" + type + "/" + n.get("documentId").asText() + "-" + type.toLowerCase() + "_scan_1_.pdf");

            HttpResponse<String> put = http.send(HttpRequest.newBuilder(URI.create(n.get("uploadUrl").asText()))
                    .header("Content-Type", "application/pdf")
                    .PUT(HttpRequest.BodyPublishers.ofString("%PDF-1.4 test", StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(put.statusCode()).as("PUT to the pre-signed URL").isEqualTo(200);
            docs.put(type, new String[]{n.get("documentId").asText(), key});
        }
        return docs;
    }

    private void sendResult(UUID claimId, String type, String[] doc, String status) {
        String body = """
                {"documentId":"%s","claimId":"%s","s3Key":"%s","docType":"%s","status":"%s","checksum":"sha256:abc","checkedAt":"2026-11-18T10:00:00Z"}
                """.formatted(doc[0], claimId, doc[1], type, status);
        IntakeContainers.RESOURCES.sqs().sendMessage(b -> b.queueUrl(IntakeContainers.RESOURCES.queueUrl()).messageBody(body));
    }

    private String statusOf(Holder h, UUID claimId) throws Exception {
        String s = mvc.perform(get("/api/claims/{id}/status", claimId).with(as(h))).andReturn().getResponse().getContentAsString();
        return json.readTree(s).get("status").asText();
    }

    private void awaitQueueEmpty() {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            var attrs = IntakeContainers.RESOURCES.sqs().getQueueAttributes(b -> b.queueUrl(IntakeContainers.RESOURCES.queueUrl())
                    .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                            QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)).attributes();
            assertThat(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)).isEqualTo("0");
            assertThat(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)).isEqualTo("0");
        });
    }

    @Test
    void createUploadVerify_reachesUnderReview_withChecksRecordedAndFullHistory() throws Exception {
        IntakeTestConfig.ELIGIBILITY.delayMs = 400;
        IntakeTestConfig.ASSESSMENT.delayMs = 400;
        Holder h = newHolder();
        UUID claimId = createClaim(h);

        Map<String, String[]> docs = uploadAll(h, claimId);
        for (String[] d : docs.values()) {
            assertThat(IntakeContainers.RESOURCES.s3().headObject(b -> b.bucket(IntakeContainers.BUCKET).key(d[1])).contentLength())
                    .as("object is in S3").isPositive();
        }
        docs.forEach((type, d) -> sendResult(claimId, type, d, "VERIFIED"));

        await().atMost(Duration.ofSeconds(30)).until(() -> statusOf(h, claimId).equals("UNDER_REVIEW"));

        String body = mvc.perform(get("/api/claims/{id}", claimId).with(adjuster()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligibility").value("ELIGIBLE"))
                .andExpect(jsonPath("$.proposedAmount").value(67500.00))
                .andExpect(jsonPath("$.eligibilityCheck.eligible").value(true))
                .andExpect(jsonPath("$.eligibilityCheck.checks[0].rule").value("POLICY_ACTIVE"))
                .andExpect(jsonPath("$.documents.length()").value(4))
                .andReturn().getResponse().getContentAsString();
        JsonNode claim = json.readTree(body);
        for (JsonNode d : claim.get("documents")) {
            assertThat(d.get("status").asText()).isEqualTo("VERIFIED");
            assertThat(d.get("previewUrl").asText()).startsWith("http");
        }
        List<String> path = new ArrayList<>();
        claim.get("statusHistory").forEach(s -> path.add(s.get("toStatus").asText()));
        assertThat(path).containsExactly("SUBMITTED", "DOCS_PENDING", "DOCS_VERIFIED", "ELIGIBILITY_CHECKED", "ASSESSED", "UNDER_REVIEW");
        assertThat(claim.get("statusHistory").get(0).get("fromStatus").isNull()).isTrue();
        assertThat(eligibilityChecks.findByClaimIdOrderByCheckedAtAsc(claimId)).hasSize(1);

        // Eligibility and assessment ran in parallel: each started before the other finished.
        FakeChecks.Call el = IntakeTestConfig.ELIGIBILITY.calls.stream().filter(c -> c.claimId().equals(claimId.toString())).findFirst().orElseThrow();
        FakeChecks.Call as = IntakeTestConfig.ASSESSMENT.calls.stream().filter(c -> c.claimId().equals(claimId.toString())).findFirst().orElseThrow();
        assertThat(el.startNanos()).isLessThan(as.endNanos());
        assertThat(as.startNanos()).isLessThan(el.endNanos());

        // In the adjuster queue, and the results were deleted from SQS.
        mvc.perform(get("/api/claims/queue").param("size", "100").with(adjuster()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '" + claimId + "')]").exists());
        awaitQueueEmpty();

        // Events: one CLAIM_CREATED with policyNo and amount, 4 DOCUMENT_VERIFIED, a status event per change.
        List<ClaimEvent> ev = events().forClaim(claimId);
        ClaimEvent created = ev.stream().filter(e -> e.eventType().equals("CLAIM_CREATED")).findFirst().orElseThrow();
        assertThat(created.policyNo()).isEqualTo(h.policyNo());
        assertThat(created.amount()).isEqualByComparingTo("82000");
        assertThat(created.occurredAt()).isNotNull();
        assertThat(ev.stream().filter(e -> e.eventType().equals("DOCUMENT_VERIFIED"))).hasSize(4);
        assertThat(ev.stream().filter(e -> e.eventType().equals("CLAIM_STATUS_CHANGED")).map(ClaimEvent::toStatus))
                .containsExactly("DOCS_PENDING", "DOCS_VERIFIED", "ELIGIBILITY_CHECKED", "ASSESSED", "UNDER_REVIEW");
        assertThat(events().typesFor(claimId)).contains("ELIGIBILITY_CHECKED", "ASSESSMENT_COMPLETED");
    }

    @Test
    void aDuplicateResultMessageDoesNotChangeStateTwice() throws Exception {
        Holder h = newHolder();
        UUID claimId = createClaim(h);
        Map<String, String[]> docs = uploadAll(h, claimId);

        // Every result twice, interleaved.
        docs.forEach((type, d) -> sendResult(claimId, type, d, "VERIFIED"));
        docs.forEach((type, d) -> sendResult(claimId, type, d, "VERIFIED"));

        await().atMost(Duration.ofSeconds(30)).until(() -> statusOf(h, claimId).equals("UNDER_REVIEW"));
        awaitQueueEmpty(); // both copies were consumed and deleted

        assertThat(history.findByClaimIdOrderByIdAsc(claimId).stream()
                .filter(x -> x.getToStatus() == ClaimStatus.DOCS_VERIFIED)).hasSize(1);
        assertThat(history.findByClaimIdOrderByIdAsc(claimId)).hasSize(6);
        assertThat(eligibilityChecks.findByClaimIdOrderByCheckedAtAsc(claimId)).hasSize(1);
        assertThat(events().typesFor(claimId).stream().filter("DOCUMENT_VERIFIED"::equals)).hasSize(4);
        assertThat(IntakeTestConfig.ELIGIBILITY.calls.stream().filter(c -> c.claimId().equals(claimId.toString()))).hasSize(1);
    }

    @Test
    void aRejectedDocumentKeepsTheClaimPending_andALaterOppositeResultIsIgnored() throws Exception {
        Holder h = newHolder();
        UUID claimId = createClaim(h);
        Map<String, String[]> docs = uploadAll(h, claimId);

        sendResult(claimId, "FINAL_BILL", docs.get("FINAL_BILL"), "REJECTED");
        docs.forEach((type, d) -> { if (!type.equals("FINAL_BILL")) sendResult(claimId, type, d, "VERIFIED"); });
        awaitQueueEmpty();
        sendResult(claimId, "FINAL_BILL", docs.get("FINAL_BILL"), "VERIFIED"); // contradicts the first result
        awaitQueueEmpty();

        assertThat(statusOf(h, claimId)).isEqualTo("DOCS_PENDING");
        mvc.perform(get("/api/claims/{id}/documents", claimId).with(as(h)))
                .andExpect(jsonPath("$[?(@.docType == 'FINAL_BILL')].status").value("REJECTED"))
                .andExpect(jsonPath("$[?(@.docType == 'FINAL_BILL')].reason").value("Rejected by document verification"));

        // Re-upload replaces the rejected one; verifying it completes the set.
        String res = mvc.perform(post("/api/claims/{id}/documents/upload-url", claimId).with(as(h))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"bill-v2.pdf\",\"contentType\":\"application/pdf\",\"docType\":\"FINAL_BILL\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode n = json.readTree(res);
        sendResult(claimId, "FINAL_BILL", new String[]{n.get("documentId").asText(), n.get("key").asText()}, "VERIFIED");

        await().atMost(Duration.ofSeconds(30)).until(() -> statusOf(h, claimId).equals("UNDER_REVIEW"));
        mvc.perform(get("/api/claims/{id}/documents", claimId).with(as(h)))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[?(@.docType == 'FINAL_BILL')].fileName").value("bill-v2.pdf"));
    }
}
