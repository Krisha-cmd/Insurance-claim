package com.claims.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** Where a policyholder is paid. Only a masked account number is stored. */
@Entity
@Table(name = "payee_account")
public class PayeeAccount {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "account_holder", nullable = false)
    private String accountHolder;

    @Column(name = "account_no_masked", nullable = false)
    private String accountNoMasked;

    @Column(nullable = false)
    private String ifsc;

    @Column(nullable = false)
    private boolean verified;

    protected PayeeAccount() {
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getAccountHolder() { return accountHolder; }
    public String getAccountNoMasked() { return accountNoMasked; }
    public String getIfsc() { return ifsc; }
    public boolean isVerified() { return verified; }
}
