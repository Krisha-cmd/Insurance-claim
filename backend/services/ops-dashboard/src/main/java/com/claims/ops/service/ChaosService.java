package com.claims.ops.service;

import com.claims.common.error.ApiException;
import com.claims.common.event.ClaimEvent;
import com.claims.common.event.EventPublisher;
import com.claims.ops.api.CurrentUser;
import com.claims.ops.api.dto.AuditEventResponse;
import com.claims.ops.api.dto.ChaosRequest;
import com.claims.ops.api.dto.ChaosStatus;
import com.claims.ops.client.ApprovalChaosClient;
import com.claims.ops.client.PaymentChaosClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The demo's chaos switch: PAYMENT → Payment's /internal/chaos (→ gateway simulator), COORDINATOR → Approval's
 * /internal/chaos. Translates the frontend's mode names (FAIL, OFF) to the services' (FAIL_AUTHORIZE, NONE) and
 * back, and emits CHAOS_CHANGED on claim-events.
 */
@Service
public class ChaosService {

    private static final Logger log = LoggerFactory.getLogger(ChaosService.class);
    static final String INVALID = "Invalid chaos target or mode.";

    private static final Set<String> PAYMENT_MODES = Set.of("NONE", "FAIL_AUTHORIZE", "FAIL_CAPTURE", "SLOW");
    private static final Set<String> COORDINATOR_MODES = Set.of("NONE", "CRASH_AFTER_PRECOMMIT", "CRASH_BEFORE_PRECOMMIT");

    private record Change(Instant at, AuditEventResponse.Actor by) {
    }

    private final ApprovalChaosClient approval;
    private final PaymentChaosClient payment;
    private final EventPublisher events;
    private final Clock clock;
    private final Map<String, Change> lastChange = new ConcurrentHashMap<>();

    public ChaosService(ApprovalChaosClient approval, PaymentChaosClient payment, EventPublisher events, Clock clock) {
        this.approval = approval;
        this.payment = payment;
        this.events = events;
        this.clock = clock;
    }

    public ChaosStatus set(ChaosRequest req, CurrentUser user) {
        String target = req.target().trim().toUpperCase();
        String mode = req.mode().trim().toUpperCase();
        String serviceMode;
        switch (target) {
            case "PAYMENT" -> {
                serviceMode = switch (mode) {
                    case "FAIL" -> "FAIL_AUTHORIZE";
                    case "OFF" -> "NONE";
                    default -> mode;
                };
                if (!PAYMENT_MODES.contains(serviceMode)) throw new ApiException(HttpStatus.BAD_REQUEST, INVALID);
                call(target, () -> payment.set(serviceMode, req.delayMs() == null ? 0 : req.delayMs()));
            }
            case "COORDINATOR" -> {
                serviceMode = "OFF".equals(mode) ? "NONE" : mode;
                if (!COORDINATOR_MODES.contains(serviceMode)) throw new ApiException(HttpStatus.BAD_REQUEST, INVALID);
                call(target, () -> approval.set(serviceMode));
            }
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, INVALID);
        }

        Instant now = clock.instant();
        AuditEventResponse.Actor by = new AuditEventResponse.Actor(user.id().toString(), user.name(), user.role());
        lastChange.put(target, new Change(now, by));
        events.publish(ClaimEvent.builder("CHAOS_CHANGED", "ops-dashboard", null, now)
                .actor(new ClaimEvent.Actor(user.id().toString(), user.role(), user.name()))
                .detail("target", target).detail("mode", uiMode(target, serviceMode)).detail("serviceMode", serviceMode)
                .build());
        log.warn("Chaos {} set to {} by {}", target, serviceMode, user.name());
        return current();
    }

    /** Both targets as the services report them now; an unreachable one is UNKNOWN with the error. */
    public ChaosStatus current() {
        ChaosStatus.Target p;
        try {
            PaymentChaosClient.Mode m = payment.get();
            p = target("PAYMENT", m.mode(), m.delayMs(), null);
        } catch (RestClientException e) {
            p = target("PAYMENT", null, null, e.getMessage());
        }
        ChaosStatus.Target c;
        try {
            c = target("COORDINATOR", approval.get(), null, null);
        } catch (RestClientException e) {
            c = target("COORDINATOR", null, null, e.getMessage());
        }
        return new ChaosStatus(p, c);
    }

    private ChaosStatus.Target target(String target, String serviceMode, Long delayMs, String error) {
        Change ch = lastChange.get(target);
        return new ChaosStatus.Target(error != null ? "UNKNOWN" : uiMode(target, serviceMode), serviceMode, delayMs,
                ch == null ? null : ch.at(), ch == null ? null : ch.by(), null, error == null, error);
    }

    /** The frontend's names: OFF for NONE, FAIL for the gateway's FAIL_AUTHORIZE; others unchanged. */
    static String uiMode(String target, String serviceMode) {
        if (serviceMode == null || "NONE".equals(serviceMode)) return "OFF";
        if ("PAYMENT".equals(target) && "FAIL_AUTHORIZE".equals(serviceMode)) return "FAIL";
        return serviceMode;
    }

    private static void call(String target, Runnable r) {
        try {
            r.run();
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not change chaos on " + target + ": " + e.getMessage());
        }
    }
}
