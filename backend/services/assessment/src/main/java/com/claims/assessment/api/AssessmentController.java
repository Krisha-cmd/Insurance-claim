package com.claims.assessment.api;

import com.claims.assessment.api.dto.AdjustRequest;
import com.claims.assessment.api.dto.AssessmentResponse;
import com.claims.assessment.mapper.AssessmentMapper;
import com.claims.assessment.service.AssessmentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** {@code ENDPOINTS.assessment} in frontend/src/config/api.js. Roles are enforced in SecurityConfig. */
@RestController
@RequestMapping("/api/assessments")
public class AssessmentController {

    private final AssessmentService service;
    private final AssessmentMapper mapper;

    public AssessmentController(AssessmentService service, AssessmentMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    /** The claim's owner or an adjuster. 404 until Claim Intake has had the claim assessed. */
    @GetMapping("/{claimId}")
    public AssessmentResponse get(@PathVariable UUID claimId, CurrentUser user) {
        return mapper.response(service.get(claimId, user));
    }

    /** Adjusters only: {@code {amount, comment}} with 0 &lt; amount ≤ claimed and a comment of at least 10 characters. */
    @PutMapping("/{claimId}/adjust")
    public AssessmentResponse adjust(@PathVariable UUID claimId, @Valid @RequestBody AdjustRequest body, CurrentUser user) {
        return mapper.response(service.adjust(claimId, body, user));
    }
}
