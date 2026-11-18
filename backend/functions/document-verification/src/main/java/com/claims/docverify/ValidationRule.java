package com.claims.docverify;

import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.util.Optional;

/** One check on an uploaded document. */
public interface ValidationRule {

    /** @return the reason to reject the document, or empty if it passes */
    Optional<String> check(ObjectKey key, HeadObjectResponse head);
}
