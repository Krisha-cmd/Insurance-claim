package com.claims.analytics.serde;

import com.claims.analytics.model.AnalyticsDocument;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.functions.MapFunction;

/** Turns an analytics result into the JSON message published to {@code claim-analytics}. */
public class JsonSerializer<T extends AnalyticsDocument> implements MapFunction<T, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String map(T value) throws JsonProcessingException {
        return toJson(value);
    }

    public static String toJson(AnalyticsDocument value) throws JsonProcessingException {
        return MAPPER.writeValueAsString(value.toDocument());
    }
}
