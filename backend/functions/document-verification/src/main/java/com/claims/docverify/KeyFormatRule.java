package com.claims.docverify;

import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.util.Optional;

/** The key is {@code claims/{claimId}/{docType}/{documentId}-{fileName}} with UUID ids and a known docType. */
public class KeyFormatRule implements ValidationRule {

    @Override
    public Optional<String> check(ObjectKey key, HeadObjectResponse head) {
        if (!key.wellFormed()) {
            return Optional.of("The object key is not claims/{claimId}/{docType}/{documentId}-{fileName}");
        }
        if (!Config.DOC_TYPES.contains(key.docType())) {
            return Optional.of("Unknown document type " + key.docType());
        }
        return Optional.empty();
    }
}
