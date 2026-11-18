package com.claims.docverify;

import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class Fixtures {

    static final UUID CLAIM = UUID.fromString("3f6c2a8e-1b4d-4c7a-9e2f-5d8b7a6c4e10");
    static final UUID BILL = UUID.fromString("0b9e2c71-5a3d-4f8e-8c1b-2d7e6f9a3b41");
    static final String BUCKET = "claims-dev-documents-123456789012";

    private Fixtures() {
    }

    /** An S3 event notification from src/test/resources/events. */
    static String event(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/events/" + name)) {
            if (in == null) throw new IllegalArgumentException("No fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static ObjectKey key(String docType, String fileName) {
        return ObjectKey.of(BUCKET, "claims/" + CLAIM + "/" + docType + "/" + BILL + "-" + fileName, 100L, "abc");
    }

    static HeadObjectResponse head(String contentType, long size) {
        return HeadObjectResponse.builder().contentType(contentType).contentLength(size).eTag("\"9b2cf535\"").build();
    }
}
