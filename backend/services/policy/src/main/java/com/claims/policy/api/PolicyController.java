package com.claims.policy.api;

import com.claims.policy.api.dto.CoverageResponse;
import com.claims.policy.api.dto.PolicyResponse;
import com.claims.policy.mapper.PolicyMapper;
import com.claims.policy.service.PolicyQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** ENDPOINTS.policy: mine, byNumber, coverage. */
@RestController
@RequestMapping("/api/policies")
public class PolicyController {

    private final PolicyQueryService queries;
    private final PolicyMapper mapper;

    public PolicyController(PolicyQueryService queries, PolicyMapper mapper) {
        this.queries = queries;
        this.mapper = mapper;
    }

    /**
     * POLICYHOLDER. The frontend reads one policy object here (the one in force today, else the latest), so
     * that is the default; {@code ?all=true} returns every policy of the caller, in the same order.
     */
    @GetMapping("/me")
    public Object getMine(@RequestParam(defaultValue = "false") boolean all, CurrentUser user) {
        List<PolicyResponse> mine = queries.mine(user).stream().map(mapper::policy).toList();
        return all ? mine : mine.getFirst();
    }

    /** Owner or ADJUSTER: the policy, members, plan and balance. */
    @GetMapping("/{policyNo}")
    public PolicyResponse getByNumber(@PathVariable String policyNo, CurrentUser user) {
        return mapper.policy(queries.view(policyNo, user));
    }

    /** Owner or ADJUSTER: coverage rules, deductible, co-pay, waiting periods, exclusions. */
    @GetMapping("/{policyNo}/coverage")
    public CoverageResponse getCoverage(@PathVariable String policyNo, CurrentUser user) {
        return mapper.coverage(queries.coverage(policyNo, user));
    }
}
