package com.claims.intake.api;

import com.claims.intake.domain.Actor;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/** From the login service's JWT: sub, role, name and (for policyholders) policyholderId. */
public record CurrentUser(UUID id, String role, String name, UUID policyholderId) {

    public static CurrentUser from(Jwt jwt) {
        String ph = jwt.getClaimAsString("policyholderId");
        return new CurrentUser(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("role"),
                jwt.getClaimAsString("name"), ph == null || ph.isBlank() ? null : UUID.fromString(ph));
    }

    public boolean isAdjuster() {
        return "ADJUSTER".equals(role);
    }

    public Actor actor() {
        return new Actor(id, role, name);
    }
}
