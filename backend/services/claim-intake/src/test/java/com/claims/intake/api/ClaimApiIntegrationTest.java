package com.claims.intake.api;

import com.claims.intake.domain.ClaimStatus;
import com.claims.intake.support.IntakeTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ENDPOINTS.claims through the full security chain. */
class ClaimApiIntegrationTest extends IntakeTestBase {

    private String create(Holder h) throws Exception {
        String body = mvc.perform(post("/api/claims").with(as(h)).contentType(MediaType.APPLICATION_JSON).content(claimJson(h, "12000.50")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/claims/")))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }

    private String uploadBody(String type, String contentType, long size) {
        return "{\"fileName\":\"x.pdf\",\"contentType\":\"" + contentType + "\",\"docType\":\"" + type + "\",\"size\":" + size + "}";
    }

    @Test
    void createReturnsTheFrontendShape() throws Exception {
        Holder h = newHolder();
        mvc.perform(post("/api/claims").with(as(h)).contentType(MediaType.APPLICATION_JSON).content(claimJson(h, "12000.50")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DOCS_PENDING"))
                .andExpect(jsonPath("$.policyholderId").value(h.policyholderId().toString()))
                .andExpect(jsonPath("$.policyholderName").value("Asha Verma"))
                .andExpect(jsonPath("$.patient.memberId").value(h.memberId().toString()))
                .andExpect(jsonPath("$.patient.relation").value("SELF"))
                .andExpect(jsonPath("$.hospital").value("Apollo Hospital, Chennai"))
                .andExpect(jsonPath("$.treatmentType").value("Hospitalisation"))
                .andExpect(jsonPath("$.claimedAmount").value(12000.50))
                .andExpect(jsonPath("$.proposedAmount").isEmpty())
                .andExpect(jsonPath("$.eligibility").isEmpty())
                .andExpect(jsonPath("$.documents").isEmpty())
                .andExpect(jsonPath("$.statusHistory.length()").value(2))
                .andExpect(jsonPath("$.currency").value("INR"));
    }

    @Test
    void createIsValidated() throws Exception {
        Holder h = newHolder();
        mvc.perform(post("/api/claims").with(as(h)).contentType(MediaType.APPLICATION_JSON)
                        .content(claimJson(h, "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The claimed amount must be at least 1."));
        mvc.perform(post("/api/claims").with(as(h)).contentType(MediaType.APPLICATION_JSON)
                        .content(claimJson(h, "100").replace(h.memberId().toString(), UUID.randomUUID().toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The patient is not insured under this policy."));
        mvc.perform(post("/api/claims").with(as(h)).contentType(MediaType.APPLICATION_JSON)
                        .content(claimJson(h, "100").replace(h.policyNo(), "POL-NOPE")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Policy POL-NOPE not found."));
        Holder other = newHolder();
        mvc.perform(post("/api/claims").with(as(other)).contentType(MediaType.APPLICATION_JSON).content(claimJson(h, "100")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This policy belongs to someone else."));
        mvc.perform(post("/api/claims").with(adjuster()).contentType(MediaType.APPLICATION_JSON).content(claimJson(h, "100")))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownersAndAdjustersSeeAClaim_otherPolicyholdersDoNot() throws Exception {
        Holder owner = newHolder();
        String id = create(owner);

        mvc.perform(get("/api/claims/{id}", id).with(as(owner))).andExpect(status().isOk());
        mvc.perform(get("/api/claims/{id}", id).with(adjuster())).andExpect(status().isOk());
        mvc.perform(get("/api/claims/{id}/status", id).with(as(owner)))
                .andExpect(jsonPath("$.status").value("DOCS_PENDING"))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
        mvc.perform(get("/api/claims/{id}", id).with(as(newHolder())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This claim belongs to another policyholder."));
        mvc.perform(get("/api/claims/{id}/documents", id).with(as(newHolder()))).andExpect(status().isForbidden());
        mvc.perform(get("/api/claims/{id}", UUID.randomUUID()).with(adjuster())).andExpect(status().isNotFound());
        mvc.perform(get("/api/claims/{id}", id)).andExpect(status().isUnauthorized());

        mvc.perform(get("/api/claims/me").with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get("/api/claims/me").with(as(newHolder()))).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void uploadRules() throws Exception {
        Holder owner = newHolder();
        String id = create(owner);
        String url = "/api/claims/" + id + "/documents/upload-url";

        mvc.perform(post(url).with(as(owner)).contentType(MediaType.APPLICATION_JSON).content(uploadBody("FINAL_BILL", "text/html", 10)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only PDF, JPEG and PNG files can be uploaded."));
        mvc.perform(post(url).with(as(owner)).contentType(MediaType.APPLICATION_JSON).content(uploadBody("FINAL_BILL", "image/png", 11L * 1024 * 1024)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Files can be at most 10 MB."));
        mvc.perform(post(url).with(as(owner)).contentType(MediaType.APPLICATION_JSON).content(uploadBody("SELFIE", "image/png", 10)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown document type."));
        mvc.perform(post(url).with(as(newHolder())).contentType(MediaType.APPLICATION_JSON).content(uploadBody("FINAL_BILL", "image/png", 10)))
                .andExpect(status().isForbidden());
        mvc.perform(post(url).with(adjuster()).contentType(MediaType.APPLICATION_JSON).content(uploadBody("FINAL_BILL", "image/png", 10)))
                .andExpect(status().isForbidden());
        mvc.perform(post(url).with(as(owner)).contentType(MediaType.APPLICATION_JSON).content(uploadBody("LAB_REPORT", "image/jpeg", 10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadUrl").value(org.hamcrest.Matchers.containsString("X-Amz-Signature")))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
        mvc.perform(get("/api/claims/{id}/documents", id).with(as(owner)))
                .andExpect(jsonPath("$[0].docType").value("LAB_REPORT"))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].previewUrl").isEmpty());
    }

    @Test
    void uploadsAreRefusedOnceTheClaimHasMovedOn() throws Exception {
        var c = claimIn(ClaimStatus.UNDER_REVIEW, "5000");
        // claimIn() files the claim for a random user; borrow that identity.
        Holder owner = new Holder(c.getSubmittedBy(), c.getPolicyholderId(), c.getMemberId(), c.getPolicyNo());
        mvc.perform(post("/api/claims/{id}/documents/upload-url", c.getId()).with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(uploadBody("FINAL_BILL", "application/pdf", 10)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Documents can no longer be added to this claim (status UNDER_REVIEW)."));
    }

    @Test
    void queueAndSearchAreForAdjusters() throws Exception {
        Holder h = newHolder();
        String pending = create(h);
        var review = claimIn(ClaimStatus.UNDER_REVIEW, "5000");

        mvc.perform(get("/api/claims/queue").param("size", "100").with(adjuster()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.items[*].id", hasItem(review.getId().toString())))
                .andExpect(jsonPath("$.items[*].id", not(hasItem(pending))));
        mvc.perform(get("/api/claims/queue").param("status", "DOCS_PENDING").with(adjuster()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/claims/queue").with(as(h))).andExpect(status().isForbidden());

        mvc.perform(get("/api/claims").param("q", h.policyNo().toLowerCase()).with(adjuster()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(pending));
        mvc.perform(get("/api/claims").param("status", "UNDER_REVIEW").param("size", "100").with(adjuster()))
                .andExpect(jsonPath("$.items[*].status", not(hasItem("DOCS_PENDING"))));
        mvc.perform(get("/api/claims").param("from", "2999-01-01").with(adjuster()))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/claims").param("size", "500").with(adjuster())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/claims").with(as(h))).andExpect(status().isForbidden());
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }
}
