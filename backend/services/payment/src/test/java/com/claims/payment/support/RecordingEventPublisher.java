package com.claims.payment.support;

import com.claims.common.event.ClaimEvent;
import com.claims.common.event.EventPublisher;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class RecordingEventPublisher implements EventPublisher {

    private final List<ClaimEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void publish(ClaimEvent event) {
        events.add(event);
    }

    public List<ClaimEvent> forClaim(UUID claimId) {
        return events.stream().filter(e -> claimId.equals(e.claimId())).toList();
    }

    public List<String> typesFor(UUID claimId) {
        return forClaim(claimId).stream().map(ClaimEvent::eventType).toList();
    }
}
