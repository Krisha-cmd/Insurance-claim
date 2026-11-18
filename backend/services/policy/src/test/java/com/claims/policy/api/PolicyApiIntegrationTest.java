package com.claims.policy.api;

import com.claims.policy.support.PolicyTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static com.claims.policy.support.TestPolicies.PATIENT1_HOLDER;
import static com.claims.policy.support.TestPolicies.PATIENT1_USER;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ENDPOINTS.policy against the seeded data (V2__seed.sql). */
@PolicyTest
class PolicyApiIntegrationTest {

    @Autowired MockMvc mvc;

    /** patient1 exactly as the login service issues it. */
    static RequestPostProcessor patient1() {
        return jwt().jwt(j -> j.subject(PATIENT1_USER.toString()).claim("role", "POLICYHOLDER").claim("name", "Asha Verma")
                .claim("policyholderId", PATIENT1_HOLDER.toString())).authorities(new SimpleGrantedAuthority("ROLE_POLICYHOLDER"));
    }

    static RequestPostProcessor otherPolicyholder() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "POLICYHOLDER").claim("name", "Someone Else")
                .claim("policyholderId", UUID.randomUUID().toString())).authorities(new SimpleGrantedAuthority("ROLE_POLICYHOLDER"));
    }

    static RequestPostProcessor adjuster() {
        return jwt().jwt(j -> j.subject("997bacd0-5b29-4554-b398-52e9f89bfa6c").claim("role", "ADJUSTER").claim("name", "Rohan Mehta"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADJUSTER"));
    }

    @Test
    void patient1SeesTheirPolicyInTheFrontendShape() throws Exception {
        mvc.perform(get("/api/policies/me").with(patient1()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyNumber").value("POL-2026-00017"))
                .andExpect(jsonPath("$.holderId").value(PATIENT1_HOLDER.toString()))
                .andExpect(jsonPath("$.holderName").value("Asha Verma"))
                .andExpect(jsonPath("$.plan").value("Family Health Plus"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.validFrom").value("2026-01-01"))
                .andExpect(jsonPath("$.validTo").value("2026-12-31"))
                .andExpect(jsonPath("$.sumInsured").value(500000.00))
                .andExpect(jsonPath("$.remainingBalance").isNumber())
                .andExpect(jsonPath("$.deductible").value(5000.00))
                .andExpect(jsonPath("$.coPayPercent").value(10.00))
                .andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.members[0].id").value("567dacf4-a6e5-461f-b094-8ded155a5a7e"))
                .andExpect(jsonPath("$.members[0].relation").value("SELF"))
                .andExpect(jsonPath("$.members[1].name").value("Vikram Verma"))
                .andExpect(jsonPath("$.members[1].relation").value("SPOUSE"));

        mvc.perform(get("/api/policies/me").param("all", "true").with(patient1()))
                .andExpect(jsonPath("$[*].policyNumber", hasItem("POL-2026-00017")))
                .andExpect(jsonPath("$[*].policyNumber", hasItem("POL-2026-00018")))
                .andExpect(jsonPath("$[0].policyNumber").value("POL-2026-00017"));

        mvc.perform(get("/api/policies/POL-2026-00017").with(patient1())).andExpect(status().isOk());
        mvc.perform(get("/api/policies/POL-2026-00018").with(patient1()))
                .andExpect(jsonPath("$.status").value("LAPSED"))
                .andExpect(jsonPath("$.validTo").value("2025-12-31"));
    }

    @Test
    void coverageHasTheSeededRules() throws Exception {
        mvc.perform(get("/api/policies/POL-2026-00017/coverage").with(patient1()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyNumber").value("POL-2026-00017"))
                .andExpect(jsonPath("$.deductible").value(5000.00))
                .andExpect(jsonPath("$.coPayPercent").value(10.00))
                .andExpect(jsonPath("$.roomRentLimit").value(5000.00))
                .andExpect(jsonPath("$.treatments.length()").value(5))
                .andExpect(jsonPath("$.treatments[?(@.category == 'HOSPITALISATION')].subLimit").value(300000.00))
                .andExpect(jsonPath("$.treatments[?(@.category == 'HOSPITALISATION')].treatment").value("Hospitalisation"))
                .andExpect(jsonPath("$.treatments[?(@.category == 'HOSPITALISATION')].notes").value("Room rent up to ₹5,000/day"))
                .andExpect(jsonPath("$.treatments[?(@.category == 'SURGERY')].subLimit").value(200000.00))
                .andExpect(jsonPath("$.treatments[?(@.category == 'MATERNITY')].waitingPeriodDays").value(270))
                .andExpect(jsonPath("$.treatments[?(@.category == 'DAYCARE')].treatment").value("Day care procedure"))
                .andExpect(jsonPath("$.treatments[?(@.category == 'COSMETIC')].covered").value(false))
                .andExpect(jsonPath("$.exclusions[0].keyword").value("cosmetic"));
    }

    @Test
    void anotherPolicyholderIs403_andHasNoPolicyOfTheirOwn() throws Exception {
        mvc.perform(get("/api/policies/POL-2026-00017").with(otherPolicyholder()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This policy belongs to someone else."));
        mvc.perform(get("/api/policies/POL-2026-00017/coverage").with(otherPolicyholder())).andExpect(status().isForbidden());
        mvc.perform(get("/api/policies/me").with(otherPolicyholder()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("No policy linked to this account."));
    }

    @Test
    void anAdjusterSeesEverything() throws Exception {
        mvc.perform(get("/api/policies/POL-2026-00017").with(adjuster())).andExpect(status().isOk());
        mvc.perform(get("/api/policies/POL-2026-00018").with(adjuster())).andExpect(status().isOk());
        mvc.perform(get("/api/policies/POL-2026-00018/coverage").with(adjuster())).andExpect(status().isOk());
        mvc.perform(get("/api/policies/me").with(adjuster())).andExpect(status().isForbidden());
    }

    @Test
    void unknownAndUnauthenticated() throws Exception {
        mvc.perform(get("/api/policies/POL-NOPE").with(adjuster()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Policy POL-NOPE not found."));
        mvc.perform(get("/api/policies/POL-2026-00017")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void searchWideningIsNotPossibleThroughMine() throws Exception {
        mvc.perform(get("/api/policies/me").param("all", "true").with(patient1()))
                .andExpect(jsonPath("$[*].holderId", not(hasItem(UUID.randomUUID().toString()))));
    }
}
