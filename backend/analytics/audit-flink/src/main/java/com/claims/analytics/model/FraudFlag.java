package com.claims.analytics.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Raised when one policy has more than the allowed number of new claims inside one sliding window. */
public class FraudFlag implements AnalyticsDocument {

    private String policyNo;
    private long windowStart;
    private long windowEnd;
    private int windowMinutes;
    private int claimCount;
    private List<String> claimIds = new ArrayList<>();

    public FraudFlag() {
    }

    public FraudFlag(String policyNo, long windowStart, long windowEnd, int windowMinutes, List<String> claimIds) {
        this.policyNo = policyNo;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.windowMinutes = windowMinutes;
        this.claimIds = new ArrayList<>(claimIds);
        this.claimCount = claimIds.size();
    }

    @Override
    public String type() {
        return "FraudFlag";
    }

    @Override
    public Map<String, Object> toDocument() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("type", type());
        doc.put("policyNo", policyNo);
        doc.put("windowStart", Timestamps.isoSeconds(windowStart));
        doc.put("windowEnd", Timestamps.isoSeconds(windowEnd));
        doc.put("windowMinutes", windowMinutes);
        doc.put("claimCount", claimCount);
        doc.put("claimIds", new ArrayList<>(claimIds));
        return doc;
    }

    public String getPolicyNo() { return policyNo; }
    public void setPolicyNo(String policyNo) { this.policyNo = policyNo; }
    public long getWindowStart() { return windowStart; }
    public void setWindowStart(long windowStart) { this.windowStart = windowStart; }
    public long getWindowEnd() { return windowEnd; }
    public void setWindowEnd(long windowEnd) { this.windowEnd = windowEnd; }
    public int getWindowMinutes() { return windowMinutes; }
    public void setWindowMinutes(int windowMinutes) { this.windowMinutes = windowMinutes; }
    public int getClaimCount() { return claimCount; }
    public void setClaimCount(int claimCount) { this.claimCount = claimCount; }
    public List<String> getClaimIds() { return claimIds; }
    public void setClaimIds(List<String> claimIds) { this.claimIds = claimIds; }

    @Override
    public String toString() {
        return "FraudFlag" + toDocument();
    }
}
