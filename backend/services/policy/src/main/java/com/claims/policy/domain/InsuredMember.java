package com.claims.policy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "insured_member")
public class InsuredMember {

    @Id
    private UUID id;

    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Relation relation;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    protected InsuredMember() {
    }

    public UUID getId() { return id; }
    public UUID getPolicyId() { return policyId; }
    public String getFullName() { return fullName; }
    public Relation getRelation() { return relation; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
}
