package com.claims.eligibility.api;

import com.claims.eligibility.api.dto.EligibilityResponse;
import com.claims.eligibility.api.dto.OverrideRequest;
import com.claims.eligibility.service.EligibilityService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** {@code ENDPOINTS.eligibility} in frontend/src/config/api.js. Roles are enforced in SecurityConfig. */
@RestController
@RequestMapping("/api/eligibility")
public class EligibilityController {

    private final EligibilityService service;

    public EligibilityController(EligibilityService service) {
        this.service = service;
    }

    /** The claim's owner or an adjuster: the rules re-evaluated now, merged with the latest override. */
    @GetMapping("/{claimId}")
    public EligibilityResponse get(@PathVariable UUID claimId, CurrentUser user) {
        return service.evaluate(claimId, user);
    }

    /** Adjusters only: {@code {eligible, reason, comment}}; the comment needs at least 10 characters. */
    @PutMapping("/{claimId}/override")
    public EligibilityResponse override(@PathVariable UUID claimId, @Valid @RequestBody OverrideRequest body,
                                        CurrentUser user) {
        return service.override(claimId, body, user);
    }
}
