package com.claims.login.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, Long> {

    long countByUserIdAndSuccessFalseAndAttemptedAtAfter(UUID userId, Instant after);

    Optional<LoginAttempt> findFirstByUserIdAndSuccessTrueOrderByAttemptedAtDesc(UUID userId);

    Optional<LoginAttempt> findFirstByUserIdAndSuccessFalseOrderByAttemptedAtDesc(UUID userId);
}
