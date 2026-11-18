package com.claims.intake.api;

import com.claims.intake.api.dto.ClaimResponse;
import com.claims.intake.api.dto.ClaimSummary;
import com.claims.intake.api.dto.CreateClaimRequest;
import com.claims.intake.api.dto.StatusResponse;
import com.claims.intake.domain.Claim;
import com.claims.intake.service.ClaimService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/** ENDPOINTS.claims: create, mine, byId, status. */
@RestController
@RequestMapping("/api/claims")
public class ClaimController {

    private final ClaimService claims;

    public ClaimController(ClaimService claims) {
        this.claims = claims;
    }

    /** POLICYHOLDER. 201 with the new claim (status DOCS_PENDING). */
    @PostMapping
    public ResponseEntity<ClaimResponse> create(@Valid @RequestBody CreateClaimRequest body, CurrentUser user) {
        Claim c = claims.create(body, user);
        return ResponseEntity.created(URI.create("/api/claims/" + c.getId())).body(claims.view(c.getId(), user));
    }

    /** POLICYHOLDER: the caller's claims, newest first. */
    @GetMapping("/me")
    public List<ClaimSummary> mine(CurrentUser user) {
        return claims.mine(user);
    }

    /** Owner or ADJUSTER: the claim with documents, eligibility and status history. */
    @GetMapping("/{id}")
    public ClaimResponse byId(@PathVariable UUID id, CurrentUser user) {
        return claims.view(id, user);
    }

    /** Owner or ADJUSTER: {id, status, updatedAt}, polled by the frontend. */
    @GetMapping("/{id}/status")
    public StatusResponse status(@PathVariable UUID id, CurrentUser user) {
        return claims.status(id, user);
    }
}
