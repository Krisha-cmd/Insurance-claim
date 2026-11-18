package com.claims.policy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "policy")
public class Policy {

    @Id
    private UUID id;

    @Column(name = "policy_no", nullable = false, unique = true)
    private String policyNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "policyholder_id", nullable = false)
    private Policyholder policyholder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "sum_insured", nullable = false, precision = 14, scale = 2)
    private BigDecimal sumInsured;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PolicyStatus status;

    protected Policy() {
    }

    /** In force on {@code day}: ACTIVE and the day is within the policy period. */
    public boolean isInForceOn(LocalDate day) {
        return status == PolicyStatus.ACTIVE && !day.isBefore(startDate) && !day.isAfter(endDate);
    }

    public UUID getId() { return id; }
    public String getPolicyNo() { return policyNo; }
    public Policyholder getPolicyholder() { return policyholder; }
    public Plan getPlan() { return plan; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public BigDecimal getSumInsured() { return sumInsured; }
    public PolicyStatus getStatus() { return status; }
}
