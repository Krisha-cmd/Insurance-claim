package com.claims.intake.api;

import com.claims.intake.api.dto.ClaimSummary;
import com.claims.intake.api.dto.PageResponse;
import com.claims.intake.domain.ClaimStatus;
import com.claims.intake.service.ClaimService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** ENDPOINTS.claims: queue, all. ADJUSTER only. */
@RestController
@RequestMapping("/api/claims")
public class AdjusterQueueController {

    private final ClaimService claims;

    public AdjusterQueueController(ClaimService claims) {
        this.claims = claims;
    }

    /** Claims in UNDER_REVIEW (or another reviewable ?status=), oldest first. */
    @GetMapping("/queue")
    public PageResponse<ClaimSummary> queue(@RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "50") int size,
                                            @RequestParam(required = false) ClaimStatus status) {
        return claims.queue(page, size, status);
    }

    /** Search by status, q (claim no or policy no), created from/to (yyyy-MM-dd, inclusive), newest first. */
    @GetMapping
    public PageResponse<ClaimSummary> search(@RequestParam(required = false) ClaimStatus status,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "50") int size) {
        return claims.search(status, q, from, to, page, size);
    }
}
