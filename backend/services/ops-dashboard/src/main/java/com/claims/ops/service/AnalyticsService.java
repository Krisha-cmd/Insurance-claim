package com.claims.ops.service;

import com.claims.common.error.ApiException;
import com.claims.ops.api.dto.FraudFlagResponse;
import com.claims.ops.api.dto.SeriesPoint;
import com.claims.ops.api.dto.SeriesResponse;
import com.claims.ops.api.dto.SummaryResponse;
import com.claims.ops.config.OpsProperties;
import com.claims.ops.repository.AnalyticsItem;
import com.claims.ops.repository.AnalyticsRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * KPIs, series and fraud flags from the Flink job's items:
 * {@code METRIC#claims_overview#1m / WINDOW#<start>} (one per closed minute that had events) and
 * {@code FRAUD#<policyNo> / WINDOW#<end>} (GSI1PK = TYPE#FraudFlag).
 */
@Service
public class AnalyticsService {

    static final String OVERVIEW_PK = "METRIC#claims_overview#1m";
    static final Duration MAX_SERIES = Duration.ofHours(24);
    static final Duration FRAUD_LOOKBACK = Duration.ofHours(24);

    private final AnalyticsRepository repo;
    private final Clock clock;
    private final int lookback;

    public AnalyticsService(AnalyticsRepository repo, Clock clock, OpsProperties props) {
        this.repo = repo;
        this.clock = clock;
        this.lookback = props.lookbackMinutes();
    }

    /** The latest window (newest SK, limit 1) for the "last minute" tile, and totals over the lookback. */
    public SummaryResponse summary() {
        Instant now = clock.instant();
        Instant lastClosed = minute(now).minus(1, ChronoUnit.MINUTES);
        Instant from = lastClosed.minus(lookback - 1L, ChronoUnit.MINUTES);

        List<AnalyticsItem> latest = repo.queryByPk(OVERVIEW_PK, true, 1);
        List<AnalyticsItem> windows = repo.queryByPkFrom(OVERVIEW_PK, "WINDOW#" + iso(from), lookback + 5);

        long approved = 0, rejected = 0, created = 0, aborts = 0, commits = 0;
        BigDecimal claimed = BigDecimal.ZERO;
        for (AnalyticsItem w : windows) {
            Map<String, Object> counts = w.map("countsByStatus");
            approved += count(counts, "APPROVED");
            rejected += count(counts, "REJECTED");
            created += w.number("claimsCreated");
            aborts += w.number("settlementAborts");
            commits += w.number("settlementCommits");
            if (w.get("totalClaimedInr") instanceof Number n) claimed = claimed.add(new BigDecimal(n.toString()));
        }

        AnalyticsItem last = latest.isEmpty() ? null : latest.getFirst();
        boolean lastIsRecent = last != null && !Instant.parse(last.text("windowStart")).isBefore(from);
        Instant windowStart = last != null ? Instant.parse(last.text("windowStart")) : lastClosed;
        Instant windowEnd = last != null ? Instant.parse(last.text("windowEnd")) : lastClosed.plus(1, ChronoUnit.MINUTES);
        long decided = approved + rejected;
        return new SummaryResponse(windowStart, windowEnd, lastIsRecent ? last.number("claimsCreated") : 0,
                decided == 0 ? null : (double) approved / decided,
                created == 0 ? null : claimed.divide(BigDecimal.valueOf(created), 0, RoundingMode.HALF_UP),
                aborts, commits, lookback, now);
    }

    /**
     * One point per minute from {@code from} (default: the start of the lookback) to the last closed minute.
     * Minutes without an item (no events) are zeros, so the charts have no gaps.
     */
    public SeriesResponse series(String metric, String window, Instant from) {
        String m = metric == null || metric.isBlank() ? "claims_by_status" : metric;
        if (!m.equals("claims_by_status") && !m.equals("aborts")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "metric must be claims_by_status or aborts");
        }
        if (window != null && !window.isBlank() && !window.equals("1m")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only the 1m window is available");
        }
        Instant lastClosed = minute(clock.instant()).minus(1, ChronoUnit.MINUTES);
        Instant start = from != null ? minute(from) : lastClosed.minus(lookback - 1L, ChronoUnit.MINUTES);
        if (start.isBefore(lastClosed.minus(MAX_SERIES))) start = lastClosed.minus(MAX_SERIES);

        Map<Instant, AnalyticsItem> byStart = new HashMap<>();
        int max = (int) Duration.between(start, lastClosed).toMinutes() + 2;
        for (AnalyticsItem w : repo.queryByPkFrom(OVERVIEW_PK, "WINDOW#" + iso(start), max)) {
            byStart.put(Instant.parse(w.text("windowStart")), w);
        }
        List<SeriesPoint> points = new ArrayList<>();
        for (Instant t = start; !t.isAfter(lastClosed); t = t.plus(1, ChronoUnit.MINUTES)) {
            AnalyticsItem w = byStart.get(t);
            if (m.equals("aborts")) {
                points.add(SeriesPoint.outcomes(t, w == null ? 0 : w.number("settlementAborts"), w == null ? 0 : w.number("settlementCommits")));
            } else {
                Map<String, Object> c = w == null ? Map.of() : w.map("countsByStatus");
                points.add(SeriesPoint.byStatus(t, w == null ? 0 : w.number("claimsCreated"),
                        count(c, "APPROVED"), count(c, "REJECTED"), count(c, "SETTLED")));
            }
        }
        return new SeriesResponse(m, "1m", points);
    }

    /**
     * Flags from the last 24 h, newest first, one per policy: a burst raises a flag in every sliding window that
     * contains it, so only the newest window per policy is shown.
     */
    public List<FraudFlagResponse> fraudFlags() {
        String since = iso(clock.instant().minus(FRAUD_LOOKBACK));
        Map<String, FraudFlagResponse> byPolicy = new LinkedHashMap<>();
        for (AnalyticsItem f : repo.queryGsi1("TYPE#FraudFlag", since, null, true, 1000)) {
            String policy = f.text("policyNo");
            if (policy == null || byPolicy.containsKey(policy)) continue;
            @SuppressWarnings("unchecked")
            List<Object> ids = f.get("claimIds") instanceof List<?> l ? (List<Object>) l : List.of();
            byPolicy.put(policy, new FraudFlagResponse(policy, null, (int) f.number("claimCount"),
                    Instant.parse(f.text("windowStart")), Instant.parse(f.text("windowEnd")),
                    ids.stream().map(String::valueOf).toList(), (int) f.number("windowMinutes")));
        }
        return new ArrayList<>(byPolicy.values());
    }

    private static long count(Map<String, Object> counts, String status) {
        return counts.get(status) instanceof Number n ? n.longValue() : 0L;
    }

    static Instant minute(Instant t) {
        return t.truncatedTo(ChronoUnit.MINUTES);
    }

    /** Seconds precision, as the Flink job writes window keys: 2026-11-16T14:05:00Z. */
    static String iso(Instant t) {
        return t.truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
