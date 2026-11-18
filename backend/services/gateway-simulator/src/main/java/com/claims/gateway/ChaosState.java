package com.claims.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The simulator's whole state, in memory: the failure mode and every authorization. A restart forgets
 * everything (fine for a demo; a real gateway keeps its records).
 */
@Component
public class ChaosState {

    public record Mode(FailureMode mode, long delayMs) {
    }

    public enum AuthStatus { AUTHORIZED, CAPTURED, VOIDED }

    /** One authorization, keyed by authId. */
    public static final class Auth {
        final String authId;
        final UUID paymentId;
        final String amount;
        volatile AuthStatus status = AuthStatus.AUTHORIZED;
        volatile String captureRef;

        Auth(String authId, UUID paymentId, String amount) {
            this.authId = authId;
            this.paymentId = paymentId;
            this.amount = amount;
        }
    }

    private final AtomicReference<Mode> mode = new AtomicReference<>(new Mode(FailureMode.NONE, 0));
    private final Map<UUID, Auth> byPayment = new ConcurrentHashMap<>();
    private final Map<String, Auth> byAuthId = new ConcurrentHashMap<>();
    private final long maxDelayMs;

    public ChaosState(@Value("${gateway.max-delay-ms:60000}") long maxDelayMs) {
        this.maxDelayMs = maxDelayMs;
    }

    public Mode mode() {
        return mode.get();
    }

    public Mode set(FailureMode m, long delayMs) {
        Mode next = new Mode(m == null ? FailureMode.NONE : m, Math.max(0, Math.min(delayMs, maxDelayMs)));
        mode.set(next);
        return next;
    }

    /** Same paymentId → same authorization (idempotent). */
    Auth authorize(UUID paymentId, String amount) {
        return byPayment.computeIfAbsent(paymentId, id -> {
            Auth a = new Auth("AUTH-" + UUID.randomUUID().toString().substring(0, 13).toUpperCase(), id, amount);
            byAuthId.put(a.authId, a);
            return a;
        });
    }

    Auth find(String authId) {
        return byAuthId.get(authId);
    }

    void sleepIfSlow() {
        Mode m = mode.get();
        if (m.mode() == FailureMode.SLOW && m.delayMs() > 0) {
            try {
                Thread.sleep(m.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    void reset() {
        byPayment.clear();
        byAuthId.clear();
        set(FailureMode.NONE, 0);
    }
}
