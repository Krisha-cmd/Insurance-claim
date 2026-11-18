package com.claims.intake.support;

import com.claims.common.event.EventPublisher;
import com.claims.intake.domain.Actor;
import com.claims.intake.domain.Claim;
import com.claims.intake.domain.ClaimStateMachine;
import com.claims.intake.domain.ClaimStatus;
import com.claims.intake.repository.ClaimRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

@IntakeTest
public abstract class IntakeTestBase {

    public static final UUID ADJUSTER_ID = UUID.fromString("997bacd0-5b29-4554-b398-52e9f89bfa6c");

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected ClaimRepository claims;
    @Autowired protected ClaimStateMachine stateMachine;
    @Autowired protected TransactionTemplate tx;
    @Autowired private EventPublisher eventPublisher;

    protected RecordingEventPublisher events() {
        return (RecordingEventPublisher) eventPublisher;
    }

    @BeforeEach
    @AfterEach
    void resetFakes() {
        IntakeTestConfig.ELIGIBILITY.reset();
        IntakeTestConfig.ASSESSMENT.reset();
    }

    /** A policyholder with their own policy registered in the fake Policy service. */
    public record Holder(UUID userId, UUID policyholderId, UUID memberId, String policyNo) {
    }

    protected static Holder newHolder() {
        Holder h = new Holder(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "POL-2026-" + String.format("%05d", (int) (Math.random() * 100000)));
        IntakeTestConfig.POLICY.policy(h.policyNo(), new FakePolicy.Member(h.policyholderId().toString(),
                h.memberId().toString(), "Asha Verma", "SELF"));
        return h;
    }

    protected static RequestPostProcessor as(Holder h) {
        return jwt().jwt(j -> j.subject(h.userId().toString()).claim("role", "POLICYHOLDER").claim("name", "Asha Verma")
                        .claim("policyholderId", h.policyholderId().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_POLICYHOLDER"));
    }

    protected static RequestPostProcessor adjuster() {
        return jwt().jwt(j -> j.subject(ADJUSTER_ID.toString()).claim("role", "ADJUSTER").claim("name", "Rohan Mehta"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADJUSTER"));
    }

    protected static String claimJson(Holder h, String amount) {
        return """
                {"policyNumber":"%s","patient":{"memberId":"%s","name":"Asha Verma","relation":"SELF"},
                 "hospital":"Apollo Hospital, Chennai","admissionDate":"%s","dischargeDate":"%s",
                 "diagnosis":"Typhoid fever","treatmentType":"Hospitalisation","claimedAmount":%s}
                """.formatted(h.policyNo(), h.memberId(), LocalDate.now().minusDays(5), LocalDate.now().minusDays(2), amount);
    }

    /** A claim moved along the legal path to {@code target} (for participant and gRPC tests). */
    protected Claim claimIn(ClaimStatus target, String claimedAmount) {
        List<ClaimStatus> path = List.of(ClaimStatus.DOCS_PENDING, ClaimStatus.DOCS_VERIFIED, ClaimStatus.UNDER_REVIEW,
                ClaimStatus.APPROVED);
        return tx.execute(s -> {
            Claim c = Claim.submit("CLM-TEST-" + UUID.randomUUID().toString().substring(0, 8), "POL-TEST", UUID.randomUUID(),
                    "Test Member", "SELF", UUID.randomUUID(), "Test Holder", UUID.randomUUID(), "Test Hospital",
                    LocalDate.now().minusDays(3), LocalDate.now().minusDays(1), "Test", "Hospitalisation",
                    new BigDecimal(claimedAmount), IntakeTestConfig.CLOCK.instant());
            claims.save(c);
            stateMachine.initial(c, Actor.intake());
            for (ClaimStatus step : path) {
                if (c.getStatus() == target) break;
                stateMachine.transition(c, step, Actor.intake(), "test setup");
            }
            return c;
        });
    }
}
