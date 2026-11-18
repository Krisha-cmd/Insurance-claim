package com.claims.approval.api;

import com.claims.common.event.ClaimEvent;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/** The signed-in user, from the login service's JWT (sub, role, name). */
public record CurrentUser(UUID id, String role, String name) {

    public static CurrentUser from(Jwt jwt) {
        return new CurrentUser(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("role"), jwt.getClaimAsString("name"));
    }

    public ClaimEvent.Actor actor() {
        return new ClaimEvent.Actor(id.toString(), role, name);
    }
}
