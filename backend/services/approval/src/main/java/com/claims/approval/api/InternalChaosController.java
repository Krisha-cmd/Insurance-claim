package com.claims.approval.api;

import com.claims.approval.config.ApprovalProperties;
import com.claims.approval.service.ChaosMode;
import com.claims.approval.service.ChaosSwitch;
import com.claims.common.error.ApiException;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Demo crash switch. Cluster-internal: the ingress does not route /internal, so only pods in the cluster
 * (e.g. the Ops Dashboard backend behind /api/ops/chaos) can call it. CHAOS_ENABLED=false turns it off.
 */
@RestController
@RequestMapping("/internal/chaos")
public class InternalChaosController {

    public record ChaosRequest(@NotNull(message = "mode must be NONE, CRASH_BEFORE_PRECOMMIT or CRASH_AFTER_PRECOMMIT.") ChaosMode mode) {
    }

    private final ChaosSwitch chaos;
    private final boolean enabled;

    public InternalChaosController(ChaosSwitch chaos, ApprovalProperties props) {
        this.chaos = chaos;
        this.enabled = props.chaosEnabled();
    }

    @PostMapping
    public Map<String, String> set(@Validated @RequestBody ChaosRequest body) {
        requireEnabled();
        chaos.set(body.mode());
        return Map.of("mode", chaos.mode().name());
    }

    @GetMapping
    public Map<String, String> get() {
        requireEnabled();
        return Map.of("mode", chaos.mode().name());
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Not found.");
        }
    }
}
