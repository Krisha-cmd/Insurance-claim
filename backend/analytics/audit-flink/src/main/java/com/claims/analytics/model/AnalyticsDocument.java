package com.claims.analytics.model;

import java.util.Map;

/**
 * An output that is both published to {@code claim-analytics} and stored in DynamoDB. The document is the
 * item body (without keys or ttl) as a JSON-compatible map: String, Number, Boolean, null, Map and List values.
 */
public interface AnalyticsDocument {

    /** Value of the {@code type} attribute, e.g. {@code WindowAggregate}. */
    String type();

    Map<String, Object> toDocument();
}
