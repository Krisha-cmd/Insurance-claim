package com.claims.intake.service;

import com.claims.intake.messaging.VerificationResultMessage;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HelpersTest {

    @Test
    void safeFileNames() {
        assertThat(DocumentService.safeFileName("Final bill (Apollo).PDF")).isEqualTo("Final_bill_Apollo_.PDF");
        assertThat(DocumentService.safeFileName("C:\\Users\\me\\scan.png")).isEqualTo("scan.png");
        assertThat(DocumentService.safeFileName("../../etc/passwd")).isEqualTo("passwd");
        assertThat(DocumentService.safeFileName("..")).isEqualTo("file");
        assertThat(DocumentService.safeFileName("रिपोर्ट.pdf")).isEqualTo("pdf");
        assertThat(DocumentService.safeFileName("a".repeat(150) + ".pdf")).hasSize(100).endsWith(".pdf");
    }

    @Test
    void contentTypesAreNormalised() {
        assertThat(DocumentService.normaliseContentType("Application/PDF; charset=binary")).isEqualTo("application/pdf");
    }

    @Test
    void claimNumbers() {
        assertThat(ClaimNumberGenerator.format(2026, 42)).isEqualTo("CLM-2026-000042");
        assertThat(ClaimNumberGenerator.format(2026, 1_000_042)).isEqualTo("CLM-2026-000042");
    }

    @Test
    void documentIdComesFromTheMessageOrTheKey() {
        UUID claim = UUID.randomUUID(), doc = UUID.randomUUID();
        var fromKey = new VerificationResultMessage(null, null, "claims/" + claim + "/FINAL_BILL/" + doc + "-bill.pdf",
                null, "verified", null, null, null);
        assertThat(fromKey.resolveDocumentId()).isEqualTo(doc);
        assertThat(fromKey.verificationStatus().name()).isEqualTo("VERIFIED");
        assertThatThrownBy(() -> new VerificationResultMessage(null, null, "other/key", null, "VERIFIED", null, null, null)
                .resolveDocumentId()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VerificationResultMessage(doc.toString(), null, null, null, "PENDING", null, null, null)
                .verificationStatus()).isInstanceOf(IllegalArgumentException.class);
    }
}
