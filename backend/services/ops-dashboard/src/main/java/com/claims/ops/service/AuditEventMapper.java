package com.claims.ops.service;

import com.claims.ops.api.dto.AuditEventResponse;
import com.claims.ops.repository.AnalyticsItem;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns an AuditEvent item (the shared ClaimEvent envelope, as the Flink job stored it) into the frontend's audit
 * event: the frontend's event names and the {@code before}/{@code after} fields frontend/src/lib/auditText.js reads.
 * Event types without a frontend name keep their own name, with {@code after = change}.
 */
@Component
public class AuditEventMapper {

    /** Frontend name → the event type(s) the services emit. */
    static final Map<String, List<String>> UI_TO_EMITTED = Map.ofEntries(
            Map.entry("CLAIM_SUBMITTED", List.of("CLAIM_CREATED")),
            Map.entry("STATUS_CHANGED", List.of("CLAIM_STATUS_CHANGED")),
            Map.entry("SETTLEMENT_STARTED", List.of("TX_STARTED")),
            Map.entry("SETTLEMENT_COMMITTED", List.of("CLAIM_SETTLED")),
            Map.entry("SETTLEMENT_ABORTED", List.of("TX_ABORTED")),
            Map.entry("COORDINATOR_RECOVERED", List.of("TX_RECOVERY")),
            Map.entry("CHAOS_TOGGLED", List.of("CHAOS_CHANGED")));

    static final Map<String, String> EMITTED_TO_UI = UI_TO_EMITTED.entrySet().stream()
            .flatMap(e -> e.getValue().stream().map(v -> Map.entry(v, e.getKey())))
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

    /**
     * Every event type the services emit (for a search without a type: one GSI1 query per type, since the
     * Ops Dashboard role may Query but not Scan).
     */
    public static final Set<String> KNOWN_TYPES = Set.of(
            "CLAIM_CREATED", "CLAIM_STATUS_CHANGED", "DOCUMENT_VERIFIED", "DOCUMENT_REJECTED", "ELIGIBILITY_CHECKED",
            "ELIGIBILITY_OVERRIDDEN", "ASSESSMENT_COMPLETED", "ASSESSMENT_ADJUSTED", "CHECK_FAILED",
            "CLAIM_APPROVED", "CLAIM_REJECTED", "SETTLEMENT_RETRIED", "CLAIM_SETTLED",
            "TX_STARTED", "TX_VOTE", "TX_PRECOMMIT", "TX_PRECOMMIT_ACKS", "TX_DOCOMMIT", "TX_DOCOMMIT_INCOMPLETE",
            "TX_COMMITTED", "TX_ABORT", "TX_ABORTED", "TX_RECOVERY", "TX_PARTICIPANT_STATE", "COORDINATOR_CRASHED",
            "PAYMENT_AUTHORIZED", "PAYMENT_CAPTURED", "PAYMENT_VOIDED", "PAYMENT_FAILED", "PAYMENT_CAPTURE_PENDING",
            "CHAOS_CHANGED");

    /** The emitted type(s) to query for a type the frontend asked for (its own name, or an emitted one). */
    public List<String> emittedTypes(String requested) {
        return UI_TO_EMITTED.getOrDefault(requested, List.of(requested));
    }

    public AuditEventResponse map(AnalyticsItem item) {
        String emitted = item.text("eventType");
        String type = EMITTED_TO_UI.getOrDefault(emitted, emitted);
        Map<String, Object> change = item.map("change");
        String from = item.text("fromStatus"), to = item.text("toStatus");
        BigDecimal amount = item.get("amount") instanceof Number n ? new BigDecimal(n.toString()) : null;
        String txId = item.text("txId");

        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        switch (type) {
            case "CLAIM_SUBMITTED" -> {
                after.put("status", to);
                after.put("claimedAmount", amount);
                after.put("hospital", change.get("hospital"));
                after.put("claimNo", change.get("claimNo"));
            }
            case "STATUS_CHANGED" -> {
                before.put("status", from);
                after.put("status", to);
            }
            case "ASSESSMENT_COMPLETED" -> after.put("proposedAmount", amount != null ? amount : change.get("after"));
            case "ASSESSMENT_ADJUSTED" -> {
                before.put("proposedAmount", change.get("before"));
                after.put("proposedAmount", change.get("after"));
            }
            case "CLAIM_APPROVED" -> {
                after.putAll(change);
                after.putIfAbsent("approvedAmount", amount);
            }
            case "SETTLEMENT_STARTED", "SETTLEMENT_RETRIED", "SETTLEMENT_ABORTED", "COORDINATOR_CRASHED" -> {
                after.putAll(change);
                after.put("transactionId", txId);
                if (amount != null) after.putIfAbsent("amount", amount);
                if (item.text("phase") != null) after.putIfAbsent("phase", item.text("phase"));
            }
            case "SETTLEMENT_COMMITTED" -> {
                after.putAll(change);
                after.put("transactionId", txId);
                after.putIfAbsent("amount", amount);
            }
            case "COORDINATOR_RECOVERED" -> {
                after.putAll(change);
                after.put("transactionId", txId);
                after.put("resumedFrom", change.get("fromState"));
            }
            default -> {
                after.putAll(change);
                if (from != null) before.put("status", from);
                if (to != null) after.put("status", to);
            }
        }

        Map<String, Object> actor = item.map("actor");
        String role = actor.get("role") == null ? null : actor.get("role").toString();
        String name = actor.get("name") != null ? actor.get("name").toString()
                : "SYSTEM".equals(role) ? item.text("service") : role;
        return new AuditEventResponse(item.text("eventId"), item.text("claimId"), type, emitted, item.text("service"),
                new AuditEventResponse.Actor(actor.get("id") == null ? null : actor.get("id").toString(), name, role),
                Instant.parse(item.text("occurredAt")), before.isEmpty() ? null : before, after.isEmpty() ? null : after,
                item.text("comment"), item.text("policyNo"), amount, txId, item.text("phase"), item.text("participant"),
                item.text("vote"), item.text("participantState"));
    }
}
