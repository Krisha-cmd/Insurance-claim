package com.claims.intake.domain;

import com.claims.common.event.ClaimEvent;

import java.util.UUID;

/** Who made a change: a signed-in user (id set) or a service (id null). */
public record Actor(UUID id, String role, String name) {

    public static final String SERVICE = "claim-intake";

    public static Actor system(String service) {
        return new Actor(null, "SYSTEM", service);
    }

    public static Actor intake() {
        return system(SERVICE);
    }

    /** Stored in claim_status_history.actor, e.g. "ADJUSTER:Rohan Mehta". */
    public String label() {
        return role + ":" + (name != null && !name.isBlank() ? name : id != null ? id.toString() : "unknown");
    }

    public boolean isPerson() {
        return id != null;
    }

    public ClaimEvent.Actor toEvent() {
        return new ClaimEvent.Actor(id != null ? id.toString() : "svc-" + name, role, name);
    }
}
