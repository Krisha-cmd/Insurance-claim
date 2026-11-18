package com.claims.ops.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compares real responses with the key structure of the frontend mocks' responses
 * (src/test/resources/shapes/frontend-mock-shapes.json, generated from frontend/src/api/mocks by generate-shapes.mjs).
 * Every key the frontend can read must be there with a compatible type; a null value is always accepted.
 */
public final class Shapes {

    private static final JsonNode MOCKS = load();

    private Shapes() {
    }

    public static JsonNode mock(String name) {
        JsonNode n = MOCKS.get(name);
        if (n == null) throw new IllegalArgumentException("No mock shape " + name);
        return n;
    }

    /** @param skip keys whose content varies per item (e.g. an audit event's before/after) and is checked elsewhere */
    public static void assertCovers(String mockName, JsonNode actual, Set<String> skip) {
        List<String> problems = new ArrayList<>();
        compare(mock(mockName), actual, "$", skip, problems);
        assertThat(problems).as("response of " + mockName + " vs the frontend mock").isEmpty();
    }

    private static void compare(JsonNode mock, JsonNode actual, String path, Set<String> skip, List<String> problems) {
        if (actual == null || actual.isMissingNode()) {
            problems.add(path + " is missing");
            return;
        }
        if (actual.isNull() || (mock.isTextual() && mock.asText().equals("null"))) return;
        if (mock.isObject()) {
            if (!actual.isObject()) { problems.add(path + " should be an object"); return; }
            for (Iterator<String> it = mock.fieldNames(); it.hasNext(); ) {
                String key = it.next();
                if (!actual.has(key)) { problems.add(path + "." + key + " is missing"); continue; }
                if (skip.contains(key)) continue;
                compare(mock.get(key), actual.get(key), path + "." + key, skip, problems);
            }
        } else if (mock.isArray()) {
            if (!actual.isArray()) { problems.add(path + " should be an array"); return; }
            if (mock.isEmpty()) return;
            for (int i = 0; i < actual.size(); i++) compare(mock.get(0), actual.get(i), path + "[" + i + "]", skip, problems);
        } else {
            String type = mock.asText();
            boolean ok = switch (type) {
                case "number" -> actual.isNumber();
                case "string" -> actual.isTextual();
                case "boolean" -> actual.isBoolean();
                default -> true;
            };
            if (!ok) problems.add(path + " should be a " + type + " but is " + actual.getNodeType());
        }
    }

    private static JsonNode load() {
        try (InputStream in = Shapes.class.getResourceAsStream("/shapes/frontend-mock-shapes.json")) {
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
