package com.claims.eligibility.service;

import com.claims.common.error.ApiException;
import com.claims.common.event.ClaimEvent;
import com.claims.common.event.EventPublisher;
import com.claims.eligibility.api.CurrentUser;
import com.claims.eligibility.api.dto.EligibilityResponse;
import com.claims.eligibility.api.dto.OverrideRequest;
import com.claims.eligibility.api.dto.RuleResultDto;
import com.claims.eligibility.grpc.ClaimClient;
import com.claims.eligibility.grpc.PolicyClient;
import com.claims.eligibility.rules.RuleContext;
import com.claims.eligibility.rules.RuleResult;
import com.claims.proto.claim.v1.Actor;
import com.claims.proto.claim.v1.ClaimDetails;
import com.claims.proto.claim.v1.ClaimStatus;
import com.claims.proto.claim.v1.EligibilityRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Stateless: every evaluation reads fresh facts from Claim Intake and Policy and runs the rules. Results are
 * stored by Claim Intake (its eligibility_check table): the automatic one by its workflow, an override through
 * RecordEligibility.
 */
@Service
public class EligibilityService {

    private static final Logger log = LoggerFactory.getLogger(EligibilityService.class);
    private static final String SERVICE = "eligibility";

    /** Claim Intake's ClaimService.REVIEWABLE: eligibility may be overridden only until a decision is made. */
    static final Set<ClaimStatus> REVIEWABLE =
            Set.of(ClaimStatus.ELIGIBILITY_CHECKED, ClaimStatus.ASSESSED, ClaimStatus.UNDER_REVIEW);

    /** The rules' verdict at one moment. */
    public record Evaluation(boolean eligible, List<RuleResult> results, Instant at) {
    }

    private final EligibilityEngine engine;
    private final PolicyClient policy;
    private final ClaimClient claims;
    private final EventPublisher events;
    private final Clock clock;

    public EligibilityService(EligibilityEngine engine, PolicyClient policy, ClaimClient claims, EventPublisher events,
                              Clock clock) {
        this.engine = engine;
        this.policy = policy;
        this.claims = claims;
        this.events = events;
        this.clock = clock;
    }

    /** gRPC Check (Claim Intake's workflow), and the basis of every REST answer. */
    public Evaluation check(ClaimDetails claim) {
        if (claim.getPolicyNo().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "claim.policy_no is required");
        }
        RuleContext ctx = new RuleContext(facts(claim), policy.policy(claim.getPolicyNo()),
                policy.coverage(claim.getPolicyNo()), policy.balance(claim.getPolicyNo()));
        List<RuleResult> results = engine.run(ctx);
        return new Evaluation(EligibilityEngine.eligible(results), results, clock.instant());
    }

    /**
     * REST GET: re-evaluates now and merges the latest override recorded in Claim Intake. 404 until Claim Intake
     * has recorded a first result (the check runs once every required document is verified).
     */
    public EligibilityResponse evaluate(UUID claimId, CurrentUser user) {
        ClaimDetails claim = claims.getClaim(claimId);
        if (!user.isAdjuster() && !isOwner(claim, user)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only see the eligibility of your own claims.");
        }
        if (!claim.hasLatestEligibility()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Eligibility has not been checked yet.");
        }
        Evaluation now = check(claim);
        EligibilityRecord latest = claim.getLatestEligibility();
        return response(claimId, now, latest.getOverridden() ? overrideOf(latest) : null);
    }

    /**
     * REST PUT .../override (adjusters). Records the override in Claim Intake together with the rules' current
     * results, then emits ELIGIBILITY_OVERRIDDEN {before, after, reason} with the comment.
     */
    public EligibilityResponse override(UUID claimId, OverrideRequest req, CurrentUser user) {
        String comment = req.comment().trim();
        String reason = req.reason().trim();
        if (comment.length() < 10) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "comment must be 10 to 2000 characters");
        }
        ClaimDetails claim = claims.getClaim(claimId);
        if (!claim.hasLatestEligibility()) {
            throw new ApiException(HttpStatus.CONFLICT, "Eligibility has not been checked yet.");
        }
        if (!REVIEWABLE.contains(claim.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Eligibility can only be changed before a decision is made.");
        }

        boolean before = claim.getLatestEligibility().getEligible();
        boolean after = req.eligible();
        Evaluation now = check(claim);
        claims.recordEligibility(claimId, after, now.results(), true, reason, comment, Actor.newBuilder()
                .setId(user.id().toString()).setRole("ADJUSTER").setName(user.name() == null ? "" : user.name()).build());

        Instant at = clock.instant();
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("before", before);
        change.put("after", after);
        change.put("reason", reason);
        events.publish(ClaimEvent.builder("ELIGIBILITY_OVERRIDDEN", SERVICE, claimId, at)
                .policyNo(claim.getPolicyNo())
                .actor(new ClaimEvent.Actor(user.id().toString(), "ADJUSTER", user.name()))
                .change(change)
                .comment(comment)
                .build());
        log.info("Claim {} eligibility overridden {} -> {} by {} ({})", claimId, before, after, user.id(), reason);

        return response(claimId, now, new EligibilityResponse.Override(after, reason, comment,
                new EligibilityResponse.By(user.name(), "ADJUSTER"), at));
    }

    // ------------------------------------------------------------------ helpers

    private static EligibilityResponse response(UUID claimId, Evaluation now, EligibilityResponse.Override override) {
        return new EligibilityResponse(claimId,
                override != null ? override.eligible() : now.eligible(), now.eligible(),
                now.results().stream().map(r -> new RuleResultDto(r.rule(), r.label(), r.passed(), r.message())).toList(),
                now.at(), override != null, override);
    }

    /** Claim Intake stores the actor as "ROLE:Name". */
    private static EligibilityResponse.Override overrideOf(EligibilityRecord r) {
        String by = r.getCheckedBy();
        int colon = by.indexOf(':');
        String role = colon > 0 ? by.substring(0, colon) : "ADJUSTER";
        String name = colon > 0 ? by.substring(colon + 1) : by;
        Instant at;
        try {
            at = r.getCheckedAt().isBlank() ? null : Instant.parse(r.getCheckedAt());
        } catch (DateTimeParseException e) {
            at = null;
        }
        return new EligibilityResponse.Override(r.getEligible(), blankToNull(r.getOverrideReason()),
                blankToNull(r.getOverrideComment()), new EligibilityResponse.By(name, role), at);
    }

    private static boolean isOwner(ClaimDetails c, CurrentUser user) {
        return user.id().toString().equals(c.getSubmittedBy())
                || (user.policyholderId() != null && user.policyholderId().toString().equals(c.getPolicyholderId()));
    }

    private static RuleContext.ClaimFacts facts(ClaimDetails c) {
        return new RuleContext.ClaimFacts(uuid(c.getClaimId()), c.getPolicyNo(), uuid(c.getMemberId()),
                date(c.getAdmissionDate()), c.getTreatmentCategory(), amount(c.getClaimedAmount()));
    }

    private static UUID uuid(String s) {
        try {
            return s == null || s.isBlank() ? null : UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static LocalDate date(String s) {
        try {
            return s == null || s.isBlank() ? null : LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static BigDecimal amount(String s) {
        try {
            return s == null || s.isBlank() ? null : new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
