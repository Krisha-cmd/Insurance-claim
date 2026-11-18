package com.claims.approval.api;

import com.claims.approval.api.dto.DecisionRequest;
import com.claims.approval.api.dto.DecisionResponse;
import com.claims.approval.api.dto.SettlementView;
import com.claims.approval.service.ApprovalService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** ENDPOINTS.approval in frontend/src/config/api.js. Adjusters only (see SecurityConfig). */
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final ApprovalService service;

    public ApprovalController(ApprovalService service) {
        this.service = service;
    }

    /** 200 for a rejection; 202 for an approval, whose settlement is now running (body has the txId). */
    @PostMapping("/{claimId}")
    public ResponseEntity<DecisionResponse> decide(@PathVariable UUID claimId, @Valid @RequestBody DecisionRequest body,
                                                   CurrentUser user) {
        DecisionResponse res = service.decide(claimId, body, user);
        return ResponseEntity.status(res.txId() != null ? HttpStatus.ACCEPTED : HttpStatus.OK).body(res);
    }

    /** 200 with the latest attempt, or 204 when no settlement has started (the frontend reads that as null). */
    @GetMapping("/{claimId}/settlement")
    public ResponseEntity<SettlementView> settlement(@PathVariable UUID claimId) {
        return service.view(claimId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{claimId}/settlement/retry")
    public ResponseEntity<DecisionResponse> retry(@PathVariable UUID claimId, CurrentUser user) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.retry(claimId, user));
    }
}
