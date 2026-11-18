package com.claims.analytics.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Claim metrics for one 1-minute tumbling window. */
public class ClaimsOverview implements AnalyticsDocument {

    public static final String METRIC_NAME = "claims_overview";
    public static final String WINDOW_LABEL = "1m";

    private long windowStart;
    private long windowEnd;
    private Map<String, Long> countsByStatus = new TreeMap<>();
    private double approvalRate;
    private long claimsCreated;
    private BigDecimal totalClaimed = BigDecimal.ZERO;
    private BigDecimal avgClaimed = BigDecimal.ZERO;
    private long settlementCommits;
    private long settlementAborts;

    public ClaimsOverview() {
    }

    @Override
    public String type() {
        return "WindowAggregate";
    }

    @Override
    public Map<String, Object> toDocument() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("type", type());
        doc.put("metric", METRIC_NAME);
        doc.put("window", WINDOW_LABEL);
        doc.put("windowStart", Timestamps.isoSeconds(windowStart));
        doc.put("windowEnd", Timestamps.isoSeconds(windowEnd));
        doc.put("countsByStatus", new TreeMap<>(countsByStatus));
        doc.put("approvalRate", BigDecimal.valueOf(approvalRate).setScale(4, RoundingMode.HALF_UP));
        doc.put("claimsCreated", claimsCreated);
        doc.put("totalClaimedInr", totalClaimed);
        doc.put("avgClaimedInr", avgClaimed);
        doc.put("settlementCommits", settlementCommits);
        doc.put("settlementAborts", settlementAborts);
        return doc;
    }

    public long getWindowStart() { return windowStart; }
    public void setWindowStart(long windowStart) { this.windowStart = windowStart; }
    public long getWindowEnd() { return windowEnd; }
    public void setWindowEnd(long windowEnd) { this.windowEnd = windowEnd; }
    public Map<String, Long> getCountsByStatus() { return countsByStatus; }
    public void setCountsByStatus(Map<String, Long> countsByStatus) { this.countsByStatus = countsByStatus; }
    public double getApprovalRate() { return approvalRate; }
    public void setApprovalRate(double approvalRate) { this.approvalRate = approvalRate; }
    public long getClaimsCreated() { return claimsCreated; }
    public void setClaimsCreated(long claimsCreated) { this.claimsCreated = claimsCreated; }
    public BigDecimal getTotalClaimed() { return totalClaimed; }
    public void setTotalClaimed(BigDecimal totalClaimed) { this.totalClaimed = totalClaimed; }
    public BigDecimal getAvgClaimed() { return avgClaimed; }
    public void setAvgClaimed(BigDecimal avgClaimed) { this.avgClaimed = avgClaimed; }
    public long getSettlementCommits() { return settlementCommits; }
    public void setSettlementCommits(long settlementCommits) { this.settlementCommits = settlementCommits; }
    public long getSettlementAborts() { return settlementAborts; }
    public void setSettlementAborts(long settlementAborts) { this.settlementAborts = settlementAborts; }

    @Override
    public String toString() {
        return "ClaimsOverview" + toDocument();
    }
}
