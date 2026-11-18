package com.claims.intake.mapper;

import com.claims.intake.api.dto.ClaimResponse;
import com.claims.intake.api.dto.ClaimSummary;
import com.claims.intake.api.dto.DocumentResponse;
import com.claims.intake.api.dto.StatusResponse;
import com.claims.intake.config.IntakeProperties;
import com.claims.intake.domain.Claim;
import com.claims.intake.domain.ClaimDocument;
import com.claims.intake.domain.ClaimStatusHistory;
import com.claims.intake.domain.EligibilityCheck;
import com.claims.intake.domain.VerificationStatus;
import com.claims.intake.storage.PresignedUrlService;
import com.claims.proto.claim.v1.ClaimDetails;
import com.claims.proto.claim.v1.RuleResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/** Entities → REST DTOs and gRPC messages. */
@Component
public class ClaimMapper {

    private static final Logger log = LoggerFactory.getLogger(ClaimMapper.class);

    private final PresignedUrlService urls;
    private final ObjectMapper json;
    private final String currency;

    public ClaimMapper(PresignedUrlService urls, ObjectMapper json, IntakeProperties props) {
        this.urls = urls;
        this.json = json;
        this.currency = props.currency();
    }

    public ClaimSummary summary(Claim c) {
        return new ClaimSummary(c.getId(), c.getClaimNo(), c.getPolicyNo(), c.getPolicyholderId(), c.getPolicyholderName(),
                patient(c), c.getHospitalName(), c.getAdmissionDate(), c.getDischargeDate(), c.getDiagnosis(),
                c.getTreatmentCategory(), c.getClaimedAmount(), c.getProposedAmount(), c.getApprovedAmount(),
                eligibility(c), c.getStatus().name(), currency, c.getCreatedAt(), c.getUpdatedAt());
    }

    public ClaimResponse full(Claim c, List<ClaimDocument> docs, EligibilityCheck latest, List<ClaimStatusHistory> history) {
        return new ClaimResponse(c.getId(), c.getClaimNo(), c.getPolicyNo(), c.getPolicyholderId(), c.getPolicyholderName(),
                patient(c), c.getHospitalName(), c.getAdmissionDate(), c.getDischargeDate(), c.getDiagnosis(),
                c.getTreatmentCategory(), c.getClaimedAmount(), c.getProposedAmount(), c.getApprovedAmount(),
                eligibility(c), c.getStatus().name(), currency, c.getRejectionReason(), null,
                documents(docs), latest == null ? null : eligibilityView(latest),
                history.stream().map(h -> new ClaimResponse.StatusChange(
                        h.getFromStatus() == null ? null : h.getFromStatus().name(), h.getToStatus().name(),
                        h.getActor(), h.getChangedBy(), h.getComment(), h.getChangedAt())).toList(),
                c.getCreatedAt(), c.getUpdatedAt());
    }

    public StatusResponse status(Claim c) {
        return new StatusResponse(c.getId(), c.getStatus().name(), c.getUpdatedAt());
    }

    /** Current documents only (a re-upload replaces the rejected one it supersedes). */
    public List<DocumentResponse> documents(List<ClaimDocument> docs) {
        return docs.stream().filter(ClaimDocument::isCurrent).map(this::document).toList();
    }

    public DocumentResponse document(ClaimDocument d) {
        String preview = d.getVerificationStatus() != VerificationStatus.PENDING && urls.isConfigured()
                ? urls.presignGet(d.getS3Key()).toString() : null;
        String status = d.getVerificationStatus().name();
        return new DocumentResponse(d.getId(), d.getDocType().name(), d.getFileName(), d.getContentType(),
                d.getSizeBytes(), status, status, d.getRejectionReason(), d.getUploadedAt(), d.getVerifiedAt(), preview);
    }

    public ClaimResponse.EligibilityView eligibilityView(EligibilityCheck e) {
        return new ClaimResponse.EligibilityView(e.isEligible(), rules(e.getRuleResultsJson()), e.getCheckedAt(),
                e.isOverridden(), e.getOverrideComment());
    }

    public List<ClaimResponse.Rule> rules(String ruleResultsJson) {
        try {
            return json.readValue(ruleResultsJson, new TypeReference<List<ClaimResponse.Rule>>() { });
        } catch (JsonProcessingException e) {
            log.warn("Unreadable rule_results: {}", e.getMessage());
            return List.of();
        }
    }

    public String rulesJson(List<RuleResult> rules) {
        try {
            return json.writeValueAsString(rules.stream()
                    .map(r -> new ClaimResponse.Rule(r.getRule(), r.getLabel(), r.getPassed(), r.getDetail())).toList());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What Eligibility and Assessment receive. */
    public ClaimDetails details(Claim c) {
        return ClaimDetails.newBuilder()
                .setClaimId(c.getId().toString())
                .setClaimNo(c.getClaimNo())
                .setPolicyNo(c.getPolicyNo())
                .setMemberId(c.getMemberId().toString())
                .setPolicyholderId(c.getPolicyholderId().toString())
                .setHospitalName(c.getHospitalName())
                .setAdmissionDate(c.getAdmissionDate().toString())
                .setDischargeDate(c.getDischargeDate().toString())
                .setDiagnosis(c.getDiagnosis())
                .setTreatmentCategory(c.getTreatmentCategory())
                .setClaimedAmount(c.getClaimedAmount().toPlainString())
                .setProposedAmount(text(c.getProposedAmount()))
                .setApprovedAmount(text(c.getApprovedAmount()))
                .setStatus(com.claims.proto.claim.v1.ClaimStatus.valueOf(c.getStatus().name()))
                .setCurrency(currency)
                .build();
    }

    private static String text(BigDecimal v) {
        return v == null ? "" : v.toPlainString();
    }

    private static ClaimSummary.Patient patient(Claim c) {
        return new ClaimSummary.Patient(c.getMemberId(), c.getMemberName(), c.getMemberRelation());
    }

    private static String eligibility(Claim c) {
        return c.getEligible() == null ? null : c.getEligible() ? "ELIGIBLE" : "INELIGIBLE";
    }
}
