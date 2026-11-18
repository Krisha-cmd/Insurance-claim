package com.claims.intake.service;

import com.claims.common.error.ApiException;
import com.claims.intake.api.CurrentUser;
import com.claims.intake.api.dto.ClaimResponse;
import com.claims.intake.api.dto.ClaimSummary;
import com.claims.intake.api.dto.CreateClaimRequest;
import com.claims.intake.api.dto.PageResponse;
import com.claims.intake.api.dto.StatusResponse;
import com.claims.intake.domain.Actor;
import com.claims.intake.domain.Claim;
import com.claims.intake.domain.ClaimStateMachine;
import com.claims.intake.domain.ClaimStatus;
import com.claims.intake.domain.EligibilityCheck;
import com.claims.intake.grpc.PolicyClient;
import com.claims.intake.mapper.ClaimMapper;
import com.claims.intake.repository.ClaimDocumentRepository;
import com.claims.intake.repository.ClaimRepository;
import com.claims.intake.repository.ClaimStatusHistoryRepository;
import com.claims.intake.repository.EligibilityCheckRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class ClaimService {

    static final int MAX_PAGE_SIZE = 100;
    public static final Set<ClaimStatus> REVIEWABLE =
            Set.of(ClaimStatus.ELIGIBILITY_CHECKED, ClaimStatus.ASSESSED, ClaimStatus.UNDER_REVIEW);

    private final ClaimRepository claims;
    private final ClaimDocumentRepository documents;
    private final ClaimStatusHistoryRepository history;
    private final EligibilityCheckRepository eligibilityChecks;
    private final PolicyClient policy;
    private final ClaimStateMachine stateMachine;
    private final ClaimNumberGenerator numbers;
    private final IntakeEvents events;
    private final ClaimMapper mapper;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ClaimService(ClaimRepository claims, ClaimDocumentRepository documents, ClaimStatusHistoryRepository history,
                        EligibilityCheckRepository eligibilityChecks, PolicyClient policy, ClaimStateMachine stateMachine,
                        ClaimNumberGenerator numbers, IntakeEvents events, ClaimMapper mapper, TransactionTemplate tx,
                        Clock clock) {
        this.claims = claims;
        this.documents = documents;
        this.history = history;
        this.eligibilityChecks = eligibilityChecks;
        this.policy = policy;
        this.stateMachine = stateMachine;
        this.numbers = numbers;
        this.events = events;
        this.mapper = mapper;
        this.tx = tx;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ REST

    /** Validates policy and member with Policy, then creates the claim (SUBMITTED → DOCS_PENDING). */
    public Claim create(CreateClaimRequest r, CurrentUser user) {
        if (user.policyholderId() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Your account is not linked to a policyholder.");
        }
        if (r.dischargeDate().isBefore(r.admissionDate())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The discharge date cannot be before the admission date.");
        }
        String policyNo = r.policyNumber().trim();
        PolicyClient.Member member = policy.validateMember(policyNo, r.patient().memberId(), user.policyholderId());

        Actor actor = user.actor();
        return tx.execute(status -> {
            Claim c = Claim.submit(numbers.next(), policyNo, r.patient().memberId(), member.memberName(),
                    member.relation(), user.policyholderId(), member.policyholderName(), user.id(),
                    r.hospital().trim(), r.admissionDate(), r.dischargeDate(), r.diagnosis().trim(),
                    r.treatmentType().trim(), r.claimedAmount().setScale(2), clock.instant());
            claims.save(c);
            stateMachine.initial(c, actor);
            events.claimCreated(c, actor);
            stateMachine.transition(c, ClaimStatus.DOCS_PENDING, Actor.intake(), "Waiting for documents");
            return c;
        });
    }

    /** The claim if the user may see it: its owner, or any adjuster. */
    @Transactional(readOnly = true)
    public Claim get(UUID claimId, CurrentUser user) {
        Claim c = claims.findById(claimId).orElseThrow(() -> notFound(claimId));
        if (!user.isAdjuster() && !c.isOwnedBy(user.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This claim belongs to another policyholder.");
        }
        return c;
    }

    /** Owner only (uploads). */
    @Transactional(readOnly = true)
    public Claim getOwned(UUID claimId, CurrentUser user) {
        Claim c = claims.findById(claimId).orElseThrow(() -> notFound(claimId));
        if (!c.isOwnedBy(user.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the policyholder who filed this claim can do that.");
        }
        return c;
    }

    @Transactional(readOnly = true)
    public ClaimResponse view(UUID claimId, CurrentUser user) {
        Claim c = get(claimId, user);
        EligibilityCheck latest = eligibilityChecks.findFirstByClaimIdOrderByCheckedAtDesc(claimId).orElse(null);
        return mapper.full(c, documents.findByClaimIdOrderByUploadedAtAsc(claimId), latest,
                history.findByClaimIdOrderByIdAsc(claimId));
    }

    @Transactional(readOnly = true)
    public StatusResponse status(UUID claimId, CurrentUser user) {
        return mapper.status(get(claimId, user));
    }

    @Transactional(readOnly = true)
    public List<ClaimSummary> mine(CurrentUser user) {
        return claims.findBySubmittedByOrderByCreatedAtDesc(user.id()).stream().map(mapper::summary).toList();
    }

    /** Claims waiting for an adjuster, oldest first. Default UNDER_REVIEW; any reviewable status may be asked for. */
    @Transactional(readOnly = true)
    public PageResponse<ClaimSummary> queue(int page, int size, ClaimStatus status) {
        if (status != null && !REVIEWABLE.contains(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The queue only holds claims that are ready for review.");
        }
        Set<ClaimStatus> statuses = status != null ? Set.of(status) : Set.of(ClaimStatus.UNDER_REVIEW);
        Page<Claim> p = claims.findByStatusInOrderByCreatedAtAsc(statuses, pageRequest(page, size, Sort.unsorted()));
        return page(p);
    }

    /** Adjuster search: status, q (claim number or policy number, partial), created between from and to (inclusive). */
    @Transactional(readOnly = true)
    public PageResponse<ClaimSummary> search(ClaimStatus status, String q, LocalDate from, LocalDate to, int page, int size) {
        Specification<Claim> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (status != null) where.add(cb.equal(root.get("status"), status));
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase(Locale.ROOT).replace("%", "\\%").replace("_", "\\_") + "%";
                where.add(cb.or(cb.like(cb.lower(root.get("claimNo")), like, '\\'),
                        cb.like(cb.lower(root.get("policyNo")), like, '\\')));
            }
            if (from != null) where.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from.atStartOfDay().toInstant(ZoneOffset.UTC)));
            if (to != null) where.add(cb.lessThan(root.get("createdAt"), to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)));
            return cb.and(where.toArray(Predicate[]::new));
        };
        return page(claims.findAll(spec, pageRequest(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))));
    }

    // ------------------------------------------------------------------ other services (gRPC)

    public record StatusChange(ClaimStatus previous, ClaimStatus current, boolean changed) {
    }

    /** ClaimService.UpdateStatus (Approval). 404 unknown claim, 409 illegal transition. */
    public StatusChange updateStatus(UUID claimId, ClaimStatus to, String reason, Actor actor) {
        return tx.execute(status -> {
            Claim c = claims.findById(claimId).orElseThrow(() -> notFound(claimId));
            ClaimStatus previous = c.getStatus();
            boolean changed = stateMachine.transition(c, to, actor, blankToNull(reason));
            if (changed && to == ClaimStatus.REJECTED) {
                c.recordRejectionReason(blankToNull(reason));
                claims.save(c);
            }
            return new StatusChange(previous, c.getStatus(), changed);
        });
    }

    @Transactional(readOnly = true)
    public Claim require(UUID claimId) {
        return claims.findById(claimId).orElseThrow(() -> notFound(claimId));
    }

    /** RecordEligibility from the Eligibility service (e.g. an adjuster override). */
    public ClaimStatus recordEligibility(UUID claimId, boolean eligible, String rulesJson, boolean overridden,
                                         String overrideComment, Actor actor) {
        return tx.execute(status -> {
            Claim c = claims.findById(claimId).orElseThrow(() -> notFound(claimId));
            recordEligibility(c, eligible, rulesJson, overridden, overrideComment, actor);
            return c.getStatus();
        });
    }

    /** In the caller's transaction (the workflow). */
    public void recordEligibility(Claim c, boolean eligible, String rulesJson, boolean overridden,
                                  String overrideComment, Actor actor) {
        eligibilityChecks.save(new EligibilityCheck(c.getId(), eligible, rulesJson, overridden,
                blankToNull(overrideComment), actor, clock.instant()));
        c.recordEligibility(eligible);
        claims.save(c);
    }

    /** RecordAssessment from the Assessment service (e.g. an adjuster adjustment). */
    public ClaimStatus recordAssessment(UUID claimId, BigDecimal proposed, Actor actor) {
        return tx.execute(status -> {
            Claim c = claims.findById(claimId).orElseThrow(() -> notFound(claimId));
            recordAssessment(c, proposed);
            return c.getStatus();
        });
    }

    public void recordAssessment(Claim c, BigDecimal proposed) {
        if (proposed.signum() < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The proposed amount cannot be negative.");
        }
        c.recordProposedAmount(proposed.setScale(2, java.math.RoundingMode.HALF_UP));
        claims.save(c);
    }

    // ------------------------------------------------------------------ helpers

    private PageResponse<ClaimSummary> page(Page<Claim> p) {
        return new PageResponse<>(p.getContent().stream().map(mapper::summary).toList(), p.getNumber(), p.getSize(),
                p.getTotalElements());
    }

    private static PageRequest pageRequest(int page, int size, Sort sort) {
        if (page < 0) throw new ApiException(HttpStatus.BAD_REQUEST, "page must be 0 or more.");
        if (size < 1 || size > MAX_PAGE_SIZE) throw new ApiException(HttpStatus.BAD_REQUEST, "size must be between 1 and " + MAX_PAGE_SIZE + ".");
        return PageRequest.of(page, size, sort);
    }

    static ApiException notFound(UUID claimId) {
        return new ApiException(HttpStatus.NOT_FOUND, "Claim " + claimId + " not found.");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    Instant now() {
        return clock.instant();
    }
}
