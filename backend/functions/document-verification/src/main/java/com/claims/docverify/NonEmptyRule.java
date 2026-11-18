package com.claims.docverify;

import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.util.Optional;

/** The file has content. */
public class NonEmptyRule implements ValidationRule {

    @Override
    public Optional<String> check(ObjectKey key, HeadObjectResponse head) {
        Long size = head.contentLength();
        return size == null || size <= 0 ? Optional.of("The file is empty") : Optional.empty();
    }
}
