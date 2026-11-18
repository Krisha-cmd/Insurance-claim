package com.claims.policy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "coverage_rule")
public class CoverageRule {

    @Id
    private UUID id;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Enumerated(EnumType.STRING)
    @Column(name = "treatment_category", nullable = false)
    private TreatmentCategory treatmentCategory;

    @Column(nullable = false)
    private boolean covered;

    @Column(name = "sub_limit", precision = 14, scale = 2)
    private BigDecimal subLimit;

    @Column(name = "waiting_period_days", nullable = false)
    private int waitingPeriodDays;

    protected CoverageRule() {
    }

    public UUID getId() { return id; }
    public UUID getPlanId() { return planId; }
    public TreatmentCategory getTreatmentCategory() { return treatmentCategory; }
    public boolean isCovered() { return covered; }
    public BigDecimal getSubLimit() { return subLimit; }
    public int getWaitingPeriodDays() { return waitingPeriodDays; }
}
