package com.claims.ops.support;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The sample items of the root README's "Document Examples" (Audit Event, Window Aggregate, Fraud Flag, Settlement
 * View), in the exact attribute layout backend/analytics/audit-flink's DynamoItems writes. Times are relative to
 * the moment the tests start, so "the last 30 minutes" contains them.
 */
public final class SeedItems {

    public static final UUID CLAIM = UUID.fromString("5b1e8a2c-3d4f-4a6b-9c8d-7e6f5a4b3c21");
    /** The policyholder who filed CLAIM (the fake ClaimService answers it). */
    public static final UUID OWNER = UUID.fromString("4e33233c-a255-4c75-963a-6809cd3b0cdd");
    public static final UUID TX = UUID.fromString("9a21c0de-1111-4222-8333-944455556666");

    /** Start of the minute before the tests started: the newest closed window. */
    public static final Instant LAST_CLOSED = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(1, ChronoUnit.MINUTES);

    private SeedItems() {
    }

    static void writeAll(DynamoDbClient c, String table) {
        // Window aggregates: the last closed minute, 3 minutes before it, and one far outside the lookback.
        put(c, table, window(LAST_CLOSED, Map.of("SUBMITTED", 4L, "APPROVED", 3L, "REJECTED", 1L, "SETTLED", 2L), 4, "360000", 2, 1));
        put(c, table, window(LAST_CLOSED.minus(3, ChronoUnit.MINUTES), Map.of("SUBMITTED", 2L, "APPROVED", 1L, "SETTLED", 1L), 2, "90000", 1, 0));
        put(c, table, window(LAST_CLOSED.minus(2, ChronoUnit.HOURS), Map.of("SUBMITTED", 50L, "APPROVED", 40L, "REJECTED", 10L), 50, "5000000", 0, 9));

        // Fraud flags: POL-2026-00017 in two sliding windows (only the newest is shown), POL-2026-00042 once.
        put(c, table, fraud("POL-2026-00017", LAST_CLOSED.minus(10, ChronoUnit.MINUTES), LAST_CLOSED, List.of("c-1040", "c-1041", "c-1042")));
        put(c, table, fraud("POL-2026-00017", LAST_CLOSED.minus(11, ChronoUnit.MINUTES), LAST_CLOSED.minus(1, ChronoUnit.MINUTES), List.of("c-1040", "c-1041", "c-1042")));
        put(c, table, fraud("POL-2026-00042", LAST_CLOSED.minus(15, ChronoUnit.MINUTES), LAST_CLOSED.minus(5, ChronoUnit.MINUTES), List.of("c-2001", "c-2002", "c-2003", "c-2004")));

        // One claim's audit trail.
        Instant t = LAST_CLOSED.minus(20, ChronoUnit.MINUTES);
        put(c, table, audit("e-001", "CLAIM_CREATED", "claim-intake", t, actor(OWNER.toString(), "POLICYHOLDER", "Asha Verma"),
                null, "SUBMITTED", "82000", Map.of("claimNo", s("CLM-2026-000042"), "hospital", s("Apollo Hospital")), null, null));
        put(c, table, audit("e-002", "CLAIM_STATUS_CHANGED", "claim-intake", t.plusSeconds(1), actor("svc-claim-intake", "SYSTEM", "claim-intake"),
                "SUBMITTED", "DOCS_PENDING", null, null, "Waiting for documents", null));
        put(c, table, audit("e-003", "ASSESSMENT_ADJUSTED", "assessment", t.plusSeconds(300), actor("997bacd0-5b29-4554-b398-52e9f89bfa6c", "ADJUSTER", "Rohan Mehta"),
                "ASSESSED", "UNDER_REVIEW", null, Map.of("field", s("proposedAmount"), "before", n("82000"), "after", n("76500")),
                "Room rent above plan limit; capped per sub-limit", null));
        put(c, table, audit("e-004", "TX_STARTED", "approval", t.plusSeconds(600), actor("svc-approval", "SYSTEM", null),
                null, null, null, Map.of("attempt", n("1"), "amount", n("76500")), null, TX));
        put(c, table, audit("e-005", "CLAIM_SETTLED", "approval", t.plusSeconds(605), actor("svc-approval", "SYSTEM", "approval"),
                null, null, "76500", Map.of("currency", s("INR"), "attempt", n("1")), null, TX));
        // An event of another claim, which the trail must not include.
        Map<String, AttributeValue> other = audit("e-900", "CLAIM_STATUS_CHANGED", "claim-intake", t.plusSeconds(2),
                actor("svc-claim-intake", "SYSTEM", "claim-intake"), "DOCS_PENDING", "DOCS_VERIFIED", null, null, null, null);
        other.put("PK", s("CLAIM#" + UUID.randomUUID()));
        put(c, table, other);

        // A settlement view (Ops Dashboard does not read it, but it is part of the table).
        Map<String, AttributeValue> view = new LinkedHashMap<>();
        view.put("PK", s("TX#" + TX));
        view.put("SK", s("STATE"));
        view.put("type", s("SettlementView"));
        view.put("claimId", s(CLAIM.toString()));
        view.put("coordinator", s("approval"));
        view.put("phase", s("DONE"));
        view.put("outcome", s("COMMITTED"));
        put(c, table, view);
    }

    public static Instant firstAuditAt() {
        return LAST_CLOSED.minus(20, ChronoUnit.MINUTES);
    }

    private static Map<String, AttributeValue> window(Instant start, Map<String, Long> counts, long created, String total,
                                                      long commits, long aborts) {
        Map<String, AttributeValue> counted = new LinkedHashMap<>();
        counts.forEach((k, v) -> counted.put(k, n(v.toString())));
        long decided = counts.getOrDefault("APPROVED", 0L) + counts.getOrDefault("REJECTED", 0L);
        Map<String, AttributeValue> i = new LinkedHashMap<>();
        i.put("PK", s("METRIC#claims_overview#1m"));
        i.put("SK", s("WINDOW#" + start));
        i.put("type", s("WindowAggregate"));
        i.put("metric", s("claims_overview"));
        i.put("window", s("1m"));
        i.put("windowStart", s(start.toString()));
        i.put("windowEnd", s(start.plus(1, ChronoUnit.MINUTES).toString()));
        i.put("countsByStatus", AttributeValue.builder().m(counted).build());
        i.put("approvalRate", n(decided == 0 ? "0" : String.format("%.4f", (double) counts.getOrDefault("APPROVED", 0L) / decided)));
        i.put("claimsCreated", n(Long.toString(created)));
        i.put("totalClaimedInr", n(total));
        i.put("avgClaimedInr", n(created == 0 ? "0" : Long.toString(Long.parseLong(total) / created)));
        i.put("settlementCommits", n(Long.toString(commits)));
        i.put("settlementAborts", n(Long.toString(aborts)));
        i.put("ttl", n(Long.toString(start.plusSeconds(7 * 86400).getEpochSecond())));
        return i;
    }

    private static Map<String, AttributeValue> fraud(String policy, Instant start, Instant end, List<String> claims) {
        Map<String, AttributeValue> i = new LinkedHashMap<>();
        i.put("PK", s("FRAUD#" + policy));
        i.put("SK", s("WINDOW#" + end));
        i.put("type", s("FraudFlag"));
        i.put("policyNo", s(policy));
        i.put("windowStart", s(start.toString()));
        i.put("windowEnd", s(end.toString()));
        i.put("windowMinutes", n("10"));
        i.put("claimCount", n(Integer.toString(claims.size())));
        i.put("claimIds", AttributeValue.builder().l(claims.stream().map(SeedItems::s).toList()).build());
        i.put("ttl", n(Long.toString(end.plusSeconds(7 * 86400).getEpochSecond())));
        i.put("GSI1PK", s("TYPE#FraudFlag"));
        i.put("GSI1SK", s(end.toString()));
        return i;
    }

    private static Map<String, AttributeValue> audit(String id, String type, String service, Instant at, AttributeValue actor,
                                                     String from, String to, String amount, Map<String, AttributeValue> change,
                                                     String comment, UUID txId) {
        String ts = at.truncatedTo(ChronoUnit.MILLIS).toString();
        if (!ts.contains(".")) ts = ts.replace("Z", ".000Z");
        Map<String, AttributeValue> i = new LinkedHashMap<>();
        i.put("PK", s("CLAIM#" + CLAIM));
        i.put("SK", s("EVENT#" + ts + "#" + id));
        i.put("type", s("AuditEvent"));
        i.put("eventId", s(id));
        i.put("eventType", s(type));
        i.put("occurredAt", s(ts));
        i.put("service", s(service));
        i.put("claimId", s(CLAIM.toString()));
        i.put("policyNo", s("POL-2026-00017"));
        i.put("actor", actor);
        i.put("fromStatus", from == null ? nul() : s(from));
        i.put("toStatus", to == null ? nul() : s(to));
        i.put("amount", amount == null ? nul() : n(amount));
        i.put("change", change == null ? nul() : AttributeValue.builder().m(change).build());
        i.put("comment", comment == null ? nul() : s(comment));
        i.put("txId", txId == null ? nul() : s(txId.toString()));
        i.put("phase", nul());
        i.put("GSI1PK", s("TYPE#" + type));
        i.put("GSI1SK", s(ts));
        return i;
    }

    private static AttributeValue actor(String id, String role, String name) {
        Map<String, AttributeValue> m = new LinkedHashMap<>();
        m.put("id", s(id));
        m.put("role", s(role));
        m.put("name", name == null ? nul() : s(name));
        return AttributeValue.builder().m(m).build();
    }

    private static void put(DynamoDbClient c, String table, Map<String, AttributeValue> item) {
        c.putItem(b -> b.tableName(table).item(item));
    }

    static AttributeValue s(String v) { return AttributeValue.builder().s(v).build(); }
    static AttributeValue n(String v) { return AttributeValue.builder().n(v).build(); }
    static AttributeValue nul() { return AttributeValue.builder().nul(true).build(); }
}
