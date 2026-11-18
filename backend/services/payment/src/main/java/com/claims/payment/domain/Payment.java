package com.claims.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One payout attempt for a claim. Its id is the gateway's idempotency key for authorize. */
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    private UUID id;

    @Column(name = "claim_id", nullable = false)
    private UUID claimId;

    @Column(name = "payee_account_id", nullable = false)
    private UUID payeeAccountId;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "auth_id")
    private String authId;

    @Column(name = "gateway_ref")
    private String gatewayRef;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Payment() {
    }

    /** Not yet saved; becomes AUTHORIZED or FAILED once the gateway has answered. */
    public static Payment start(UUID claimId, UUID payeeAccountId, BigDecimal amount, String currency, Instant now) {
        Payment p = new Payment();
        p.id = UUID.randomUUID();
        p.claimId = claimId;
        p.payeeAccountId = payeeAccountId;
        p.amount = amount;
        p.currency = currency;
        p.status = PaymentStatus.FAILED;
        p.createdAt = now;
        p.updatedAt = now;
        return p;
    }

    public void authorized(String authId, Instant now) {
        this.status = PaymentStatus.AUTHORIZED;
        this.authId = authId;
        this.updatedAt = now;
    }

    public void failed(Instant now) {
        this.status = PaymentStatus.FAILED;
        this.updatedAt = now;
    }

    public void captured(String gatewayRef, Instant now) {
        require(PaymentStatus.AUTHORIZED);
        this.status = PaymentStatus.CAPTURED;
        this.gatewayRef = gatewayRef;
        this.updatedAt = now;
    }

    public void voided(Instant now) {
        require(PaymentStatus.AUTHORIZED);
        this.status = PaymentStatus.VOIDED;
        this.updatedAt = now;
    }

    private void require(PaymentStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Payment " + id + " is " + status + ", not " + expected);
        }
    }

    public UUID getId() { return id; }
    public UUID getClaimId() { return claimId; }
    public UUID getPayeeAccountId() { return payeeAccountId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public PaymentStatus getStatus() { return status; }
    public String getAuthId() { return authId; }
    public String getGatewayRef() { return gatewayRef; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
