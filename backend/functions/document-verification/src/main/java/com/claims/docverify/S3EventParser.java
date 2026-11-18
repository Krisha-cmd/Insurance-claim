package com.claims.docverify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * An SQS message body holding an S3 event notification → the created objects in it.
 * <ul>
 *   <li>Keys arrive URL-encoded ({@code +} for a space, {@code %XX}); they are decoded here.</li>
 *   <li>The {@code s3:TestEvent} S3 sends when the notification is configured has no records → empty list.</li>
 *   <li>Only {@code ObjectCreated:*} records are returned.</li>
 * </ul>
 */
public class S3EventParser {

    private final ObjectMapper json;

    public S3EventParser(ObjectMapper json) {
        this.json = json;
    }

    /** @throws IllegalArgumentException if the body is not an S3 event (retrying cannot fix that) */
    public List<ObjectKey> parse(String sqsBody) {
        JsonNode root;
        try {
            root = json.readTree(sqsBody);
        } catch (IOException e) {
            throw new IllegalArgumentException("SQS body is not JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("SQS body is not a JSON object");
        }
        if ("s3:TestEvent".equals(root.path("Event").asText())) {
            return List.of();
        }
        JsonNode records = root.path("Records");
        if (!records.isArray()) {
            throw new IllegalArgumentException("SQS body has no Records array");
        }
        List<ObjectKey> keys = new ArrayList<>();
        for (JsonNode r : records) {
            if (!r.path("eventName").asText().startsWith("ObjectCreated")) continue;
            JsonNode s3 = r.path("s3");
            String bucket = s3.path("bucket").path("name").asText(null);
            String rawKey = s3.path("object").path("key").asText(null);
            if (bucket == null || rawKey == null) {
                throw new IllegalArgumentException("S3 record without bucket or key");
            }
            JsonNode size = s3.path("object").path("size");
            String eTag = s3.path("object").path("eTag").asText(null);
            keys.add(ObjectKey.of(bucket, decode(rawKey), size.isNumber() ? size.asLong() : null, eTag));
        }
        return keys;
    }

    static String decode(String key) {
        try {
            return URLDecoder.decode(key, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return key; // malformed escape: keep it as it is, KeyFormatRule will judge it
        }
    }
}
