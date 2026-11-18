package com.claims.docverify;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentValidatorTest {

    private final Instant now = Instant.parse("2026-11-18T10:00:00Z");
    private final DocumentValidator validator = DocumentValidator.standard(
            Config.from(Map.of("RESULTS_QUEUE_URL", "q", "MAX_SIZE_MB", "10")), Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void aGoodPdfIsVerified() {
        VerificationResult r = validator.validate(Fixtures.key("FINAL_BILL", "bill.pdf"), Fixtures.head("application/pdf", 48213));
        assertThat(r.status()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(r.reason()).isNull();
        assertThat(r.claimId()).isEqualTo(Fixtures.CLAIM);
        assertThat(r.documentId()).isEqualTo(Fixtures.BILL);
        assertThat(r.docType()).isEqualTo("FINAL_BILL");
        assertThat(r.checkedAt()).isEqualTo(now);
        assertThat(r.checksum()).isEqualTo("etag:9b2cf535");
        assertThat(r.s3Key()).endsWith("-bill.pdf");
    }

    @Test
    void everyFailedRuleIsListed() {
        VerificationResult r = validator.validate(Fixtures.key("SELFIE", "a.exe"), Fixtures.head("application/x-msdownload", 0));
        assertThat(r.status()).isEqualTo(VerificationStatus.REJECTED);
        assertThat(r.reason()).isEqualTo("Unknown document type SELFIE; The file is empty; "
                + "File type application/x-msdownload is not allowed; upload a PDF, JPEG or PNG");
    }
}
