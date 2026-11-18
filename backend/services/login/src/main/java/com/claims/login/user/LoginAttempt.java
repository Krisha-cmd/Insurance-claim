package com.claims.login.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "login_attempts")
public class LoginAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null when the username does not exist. */
    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    protected LoginAttempt() {
    }

    public LoginAttempt(UUID userId, String username, boolean success, String ipAddress, Instant attemptedAt) {
        this.userId = userId;
        this.username = username;
        this.success = success;
        this.ipAddress = ipAddress;
        this.attemptedAt = attemptedAt;
    }

    public Long getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getUsername() { return username; }
    public boolean isSuccess() { return success; }
    public String getIpAddress() { return ipAddress; }
    public Instant getAttemptedAt() { return attemptedAt; }
}
