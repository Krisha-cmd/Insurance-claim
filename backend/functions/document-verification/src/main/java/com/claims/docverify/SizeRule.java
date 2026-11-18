package com.claims.docverify;

import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.util.Optional;

/** At most MAX_SIZE_MB. (A pre-signed PUT cannot limit the size, so this is where the limit is enforced.) */
public class SizeRule implements ValidationRule {

    private final long maxBytes;
    private final long maxMb;

    public SizeRule(long maxBytes) {
        this.maxBytes = maxBytes;
        this.maxMb = maxBytes / (1024 * 1024);
    }

    @Override
    public Optional<String> check(ObjectKey key, HeadObjectResponse head) {
        Long size = head.contentLength();
        if (size != null && size > maxBytes) {
            return Optional.of(String.format("The file is %.1f MB; the limit is %d MB", size / (1024.0 * 1024.0), maxMb));
        }
        return Optional.empty();
    }
}
