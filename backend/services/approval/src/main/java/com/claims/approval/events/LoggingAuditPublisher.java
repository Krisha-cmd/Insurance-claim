package com.claims.approval.events;

import com.claims.common.event.ClaimEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Used when no Kafka is configured (local runs, or the minimal AWS setup without MSK). */
public class LoggingAuditPublisher implements AuditPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingAuditPublisher.class);

    @Override
    public void publish(ClaimEvent event) {
        log.info("event {} claim={} tx={} phase={} change={}",
                event.eventType(), event.claimId(), event.txId(), event.phase(), event.change());
    }
}
