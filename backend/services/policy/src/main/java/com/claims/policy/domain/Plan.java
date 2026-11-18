package com.claims.policy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "plan")
public class Plan {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal deductible;

    /** Percent, e.g. 10.00. */
    @Column(name = "copay_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal copayPct;

    @Column(name = "room_rent_limit", precision = 14, scale = 2)
    private BigDecimal roomRentLimit;

    protected Plan() {
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public BigDecimal getDeductible() { return deductible; }
    public BigDecimal getCopayPct() { return copayPct; }
    public BigDecimal getRoomRentLimit() { return roomRentLimit; }
}
