package com.claims.docverify;

import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/** Runs every rule; any failure rejects the document, with all the reasons joined. */
public class DocumentValidator {

    private final List<ValidationRule> rules;
    private final Clock clock;

    public DocumentValidator(List<ValidationRule> rules, Clock clock) {
        this.rules = List.copyOf(rules);
        this.clock = clock;
    }

    /** The standard rule set for this configuration. */
    public static DocumentValidator standard(Config config, Clock clock) {
        return new DocumentValidator(List.of(new KeyFormatRule(), new NonEmptyRule(), new SizeRule(config.maxSizeBytes()),
                new ContentTypeRule(config.allowedTypes())), clock);
    }

    public VerificationResult validate(ObjectKey key, HeadObjectResponse head) {
        List<String> reasons = rules.stream().map(r -> r.check(key, head)).flatMap(Optional::stream).toList();
        return reasons.isEmpty()
                ? result(key, VerificationStatus.VERIFIED, null, head.eTag())
                : result(key, VerificationStatus.REJECTED, String.join("; ", reasons), head.eTag());
    }

    /** A rejection decided before any rule ran (e.g. the object no longer exists). */
    public VerificationResult reject(ObjectKey key, String reason) {
        return result(key, VerificationStatus.REJECTED, reason, key.eTag());
    }

    private VerificationResult result(ObjectKey key, VerificationStatus status, String reason, String eTag) {
        String checksum = eTag == null ? null : "etag:" + eTag.replace("\"", "");
        return new VerificationResult(key.claimId(), key.documentId(), key.docType(), status, reason, clock.instant(),
                key.key(), checksum);
    }
}
