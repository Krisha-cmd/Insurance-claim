package com.claims.payment.service;

import com.claims.payment.config.PaymentProperties;
import com.claims.payment.domain.GatewayAction;
import com.claims.payment.domain.Payment;
import com.claims.payment.domain.PaymentAttempt;
import com.claims.payment.gateway.GatewayException;
import com.claims.payment.gateway.GatewayResponse;
import com.claims.payment.gateway.PaymentGatewayClient;
import com.claims.payment.repository.PaymentAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/** Gateway calls that are always recorded in payment_attempt (in the caller's transaction). */
@Component
public class GatewayCalls {

    private static final Logger log = LoggerFactory.getLogger(GatewayCalls.class);

    private final PaymentGatewayClient gateway;
    private final PaymentAttemptRepository attempts;
    private final Clock clock;
    private final int captureAttempts;
    private final Duration captureBackoff;

    public GatewayCalls(PaymentGatewayClient gateway, PaymentAttemptRepository attempts, Clock clock, PaymentProperties props) {
        this.gateway = gateway;
        this.attempts = attempts;
        this.clock = clock;
        this.captureAttempts = props.gateway().captureAttempts();
        this.captureBackoff = props.gateway().captureBackoff();
    }

    /** One try. */
    public GatewayResponse authorize(Payment p) {
        return call(p, GatewayAction.AUTHORIZE, () -> gateway.authorize(p.getId(), p.getAmount()));
    }

    /** One try. */
    public GatewayResponse voidAuth(Payment p) {
        return call(p, GatewayAction.VOID, () -> gateway.voidAuth(p.getAuthId()));
    }

    /** One try (the recovery sweep). */
    public GatewayResponse captureOnce(Payment p) {
        return call(p, GatewayAction.CAPTURE, () -> gateway.capture(p.getAuthId()));
    }

    /**
     * Up to {@code captureAttempts} tries with exponential backoff (200 ms, 400 ms, …). Stops early on a
     * failure that will not change (4xx). Empty if every try failed.
     */
    public Optional<GatewayResponse> captureWithRetry(Payment p) {
        Duration wait = captureBackoff;
        for (int attempt = 1; attempt <= captureAttempts; attempt++) {
            try {
                return Optional.of(captureOnce(p));
            } catch (GatewayException e) {
                if (!e.retryable() || attempt == captureAttempts) {
                    log.warn("Capture of payment {} failed on attempt {}/{} ({}){}", p.getId(), attempt, captureAttempts,
                            e.code(), e.retryable() ? "" : ", not retryable");
                    return Optional.empty();
                }
                log.info("Capture of payment {} failed on attempt {}/{} ({}); retrying in {} ms", p.getId(), attempt,
                        captureAttempts, e.code(), wait.toMillis());
                sleep(wait);
                wait = wait.multipliedBy(2);
            }
        }
        return Optional.empty();
    }

    private GatewayResponse call(Payment p, GatewayAction action, Supplier<GatewayResponse> call) {
        long start = System.nanoTime();
        try {
            GatewayResponse r = call.get();
            attempts.save(new PaymentAttempt(p.getId(), action, "OK", null, elapsedMs(start), clock.instant()));
            return r;
        } catch (GatewayException e) {
            attempts.save(new PaymentAttempt(p.getId(), action, e.code(), e.getMessage(), elapsedMs(start), clock.instant()));
            throw e;
        }
    }

    private static long elapsedMs(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
