package com.claims.gateway;

import com.claims.common.error.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * The gateway's API.
 * <ul>
 *   <li>{@code POST /authorize {paymentId, amount}} → {@code {authId}}: idempotent on paymentId.</li>
 *   <li>{@code POST /capture {authId}} → {@code {captureRef}}: idempotent on authId; 404 unknown, 409 if voided.</li>
 *   <li>{@code POST /void {authId}} → 200: idempotent; 404 unknown, 409 if already captured.</li>
 *   <li>{@code POST /chaos {mode, delayMs}} / {@code GET /chaos}.</li>
 * </ul>
 * Failures look like a real gateway's: 503 with an {@code ApiError} body.
 */
@RestController
public class GatewayController {

    private static final Logger log = LoggerFactory.getLogger(GatewayController.class);

    public record AuthorizeRequest(@NotNull UUID paymentId,
                                   @NotNull @DecimalMin(value = "0.01", message = "amount must be positive") BigDecimal amount) {
    }

    public record AuthRef(@NotBlank String authId) {
    }

    public record ChaosRequest(@NotNull(message = "mode must be NONE, FAIL_AUTHORIZE, FAIL_CAPTURE or SLOW") FailureMode mode,
                               Long delayMs) {
    }

    private final ChaosState state;

    public GatewayController(ChaosState state) {
        this.state = state;
    }

    @PostMapping("/authorize")
    public ResponseEntity<?> authorize(@Valid @RequestBody AuthorizeRequest body, HttpServletRequest req) {
        state.sleepIfSlow();
        if (state.mode().mode() == FailureMode.FAIL_AUTHORIZE) {
            log.info("authorize {} → 503 (chaos)", body.paymentId());
            return unavailable("Authorization declined: gateway unavailable (simulated)", req);
        }
        ChaosState.Auth a = state.authorize(body.paymentId(), body.amount().toPlainString());
        log.info("authorize {} {} → {}", body.paymentId(), body.amount(), a.authId);
        return ResponseEntity.ok(Map.of("authId", a.authId));
    }

    @PostMapping("/capture")
    public ResponseEntity<?> capture(@Valid @RequestBody AuthRef body, HttpServletRequest req) {
        state.sleepIfSlow();
        ChaosState.Auth a = state.find(body.authId());
        if (a == null) return error(HttpStatus.NOT_FOUND, "Unknown authorization " + body.authId(), req);
        synchronized (a) {
            if (a.status == ChaosState.AuthStatus.CAPTURED) {
                return ResponseEntity.ok(Map.of("captureRef", a.captureRef)); // idempotent
            }
            if (a.status == ChaosState.AuthStatus.VOIDED) {
                return error(HttpStatus.CONFLICT, "Authorization " + a.authId + " was voided", req);
            }
            if (state.mode().mode() == FailureMode.FAIL_CAPTURE) {
                log.info("capture {} → 503 (chaos)", a.authId);
                return unavailable("Capture failed: gateway unavailable (simulated)", req);
            }
            a.captureRef = "UTR" + Long.toString(Math.abs(UUID.randomUUID().getMostSignificantBits()) % 10_000_000_000L);
            a.status = ChaosState.AuthStatus.CAPTURED;
            log.info("capture {} → {}", a.authId, a.captureRef);
            return ResponseEntity.ok(Map.of("captureRef", a.captureRef));
        }
    }

    @PostMapping("/void")
    public ResponseEntity<?> voidAuth(@Valid @RequestBody AuthRef body, HttpServletRequest req) {
        state.sleepIfSlow();
        ChaosState.Auth a = state.find(body.authId());
        if (a == null) return error(HttpStatus.NOT_FOUND, "Unknown authorization " + body.authId(), req);
        synchronized (a) {
            if (a.status == ChaosState.AuthStatus.CAPTURED) {
                return error(HttpStatus.CONFLICT, "Authorization " + a.authId + " is already captured", req);
            }
            a.status = ChaosState.AuthStatus.VOIDED;
        }
        log.info("void {}", a.authId);
        return ResponseEntity.ok(Map.of("authId", a.authId, "status", "VOIDED"));
    }

    @PostMapping("/chaos")
    public ChaosState.Mode setChaos(@Valid @RequestBody ChaosRequest body) {
        ChaosState.Mode m = state.set(body.mode(), body.delayMs() == null ? 0 : body.delayMs());
        log.warn("Chaos mode set to {} (delay {} ms)", m.mode(), m.delayMs());
        return m;
    }

    @GetMapping("/chaos")
    public ChaosState.Mode getChaos() {
        return state.mode();
    }

    private static ResponseEntity<ApiError> unavailable(String message, HttpServletRequest req) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, message, req);
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String message, HttpServletRequest req) {
        return ResponseEntity.status(status).body(ApiError.of(status.value(), message, req.getRequestURI()));
    }
}
