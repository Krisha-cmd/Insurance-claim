package com.claims.policy.repository;

import com.claims.policy.domain.Policyholder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PolicyholderRepository extends JpaRepository<Policyholder, UUID> {

    Optional<Policyholder> findByUserId(UUID userId);
}
