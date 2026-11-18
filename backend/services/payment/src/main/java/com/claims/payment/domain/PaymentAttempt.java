package com.claims.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One call to the gateway, successful or not. */
@Entity
@Table(name = "payment_attempt")
public class PaymentAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GatewayAction action;

    /** OK, HTTP_503, TIMEOUT, NETWORK … */
    @Column(name = "result_code", nullable = false)
    private String resultCode;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    protected PaymentAttempt() {
    }

    public PaymentAttempt(UUID paymentId, GatewayAction action, String resultCode, String errorMessage,
                          long durationMs, Instant attemptedAt) {
        this.paymentId = paymentId;
        this.action = action;
        this.resultCode = resultCode;
        this.errorMessage = errorMessage == null || errorMessage.length() <= 500 ? errorMessage : errorMessage.substring(0, 497) + "...";
        this.durationMs = (int) Math.min(durationMs, Integer.MAX_VALUE);
        this.attemptedAt = attemptedAt;
    }

    public Long getId() { return id; }
    public UUID getPaymentId() { return paymentId; }
    public GatewayAction getAction() { return action; }
    public String getResultCode() { return resultCode; }
    public String getErrorMessage() { return errorMessage; }
    public Integer getDurationMs() { return durationMs; }
    public Instant getAttemptedAt() { return attemptedAt; }
}
