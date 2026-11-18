package com.claims.ops.repository;

import java.util.Map;

/**
 * One item of the claims-analytics table: its keys, its type (AuditEvent, WindowAggregate, FraudFlag,
 * SettlementView) and every attribute as a JSON-like map (numbers as BigDecimal).
 */
public record AnalyticsItem(String pk, String sk, String type, Map<String, Object> data) {

    public Object get(String key) {
        return data.get(key);
    }

    public String text(String key) {
        Object v = data.get(key);
        return v == null ? null : v.toString();
    }

    public long number(String key) {
        Object v = data.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> map(String key) {
        Object v = data.get(key);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
