package com.claims.login.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    public enum Role { POLICYHOLDER, ADJUSTER }

    public enum Status { ACTIVE, LOCKED }

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "policyholder_id")
    private UUID policyholderId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected User() {
    }

    /** For tests. Accounts are otherwise created by migrations (and later by the Policy flow). */
    public User(UUID id, String username, String email, String passwordHash, Role role,
                UUID policyholderId, String fullName) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.status = Status.ACTIVE;
        this.policyholderId = policyholderId;
        this.fullName = fullName;
    }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public Status getStatus() { return status; }
    public UUID getPolicyholderId() { return policyholderId; }
    public String getFullName() { return fullName; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }

    public boolean isLocked() { return status == Status.LOCKED; }

    public void lock() { this.status = Status.LOCKED; }

    public void unlock() { this.status = Status.ACTIVE; }

    public void recordLogin(Instant at) { this.lastLoginAt = at; }
}
