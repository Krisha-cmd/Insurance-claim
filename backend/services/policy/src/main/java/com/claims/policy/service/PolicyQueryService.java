package com.claims.policy.service;

import com.claims.common.error.ApiException;
import com.claims.policy.api.CurrentUser;
import com.claims.policy.domain.CoverageRule;
import com.claims.policy.domain.InsuredMember;
import com.claims.policy.domain.Policy;
import com.claims.policy.domain.PolicyBalance;
import com.claims.policy.repository.CoverageRuleRepository;
import com.claims.policy.repository.InsuredMemberRepository;
import com.claims.policy.repository.PolicyBalanceRepository;
import com.claims.policy.repository.PolicyRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Read side: policies, members, coverage and balance, plus the "who may see it" rule. */
@Service
@Transactional(readOnly = true)
public class PolicyQueryService {

    /** Everything one response needs, loaded in one read transaction (the associations are lazy). */
    public record PolicyView(Policy policy, String holderName, List<InsuredMember> members, PolicyBalance balance) {
    }

    public record CoverageView(Policy policy, List<CoverageRule> rules) {
    }

    private final PolicyRepository policies;
    private final InsuredMemberRepository members;
    private final CoverageRuleRepository rules;
    private final PolicyBalanceRepository balances;
    private final Clock clock;

    public PolicyQueryService(PolicyRepository policies, InsuredMemberRepository members, CoverageRuleRepository rules,
                              PolicyBalanceRepository balances, Clock clock) {
        this.policies = policies;
        this.members = members;
        this.rules = rules;
        this.balances = balances;
        this.clock = clock;
    }

    public List<Policy> findForHolder(UUID holderId) {
        return policies.findByPolicyholderIdOrderByEndDateDesc(holderId);
    }

    /** @throws ApiException 404 */
    public Policy getByNumber(String policyNo) {
        return policies.findByPolicyNo(policyNo).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "Policy " + policyNo + " not found."));
    }

    /** Adjusters see every policy; a policyholder only their own. @throws ApiException 403 */
    public void assertCanView(Policy policy, CurrentUser user) {
        if (user.isAdjuster()) return;
        boolean own = policy.getPolicyholder().getId().equals(user.policyholderId())
                || policy.getPolicyholder().getUserId().equals(user.id());
        if (!own) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This policy belongs to someone else.");
        }
    }

    /** The caller's policies, the one in force today first. @throws ApiException 404 if they have none */
    public List<PolicyView> mine(CurrentUser user) {
        if (user.policyholderId() == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "No policy linked to this account.");
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        List<PolicyView> views = findForHolder(user.policyholderId()).stream()
                .sorted(Comparator.comparing((Policy p) -> !p.isInForceOn(today)).thenComparing(Policy::getEndDate, Comparator.reverseOrder()))
                .map(this::view)
                .toList();
        if (views.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "No policy linked to this account.");
        }
        return views;
    }

    public PolicyView view(String policyNo, CurrentUser user) {
        Policy p = getByNumber(policyNo);
        assertCanView(p, user);
        return view(p);
    }

    public CoverageView coverage(String policyNo, CurrentUser user) {
        Policy p = getByNumber(policyNo);
        assertCanView(p, user);
        return coverage(p);
    }

    /** For internal callers (gRPC). */
    public PolicyView view(String policyNo) {
        return view(getByNumber(policyNo));
    }

    public CoverageView coverage(String policyNo) {
        return coverage(getByNumber(policyNo));
    }

    private PolicyView view(Policy p) {
        List<InsuredMember> ms = members.findByPolicyIdOrderByRelationAscFullNameAsc(p.getId()).stream()
                .sorted(Comparator.comparing(InsuredMember::getRelation)).toList(); // SELF, SPOUSE, CHILD, PARENT
        PolicyBalance balance = balances.findById(p.getId()).orElseGet(() -> new PolicyBalance(p.getId()));
        p.getPlan().getName(); // initialise the lazy plan inside the transaction
        return new PolicyView(p, p.getPolicyholder().getFullName(), ms, balance);
    }

    private CoverageView coverage(Policy p) {
        List<CoverageRule> rs = rules.findByPlanId(p.getPlan().getId()).stream()
                .sorted(Comparator.comparing(CoverageRule::getTreatmentCategory)).toList();
        return new CoverageView(p, rs);
    }
}
