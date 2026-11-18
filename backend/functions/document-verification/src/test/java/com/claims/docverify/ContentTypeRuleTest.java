package com.claims.docverify;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ContentTypeRuleTest {

    private final ContentTypeRule rule = new ContentTypeRule(Set.of("application/pdf", "image/jpeg", "image/png"));

    @Test
    void allowedTypesPass() {
        assertThat(rule.check(Fixtures.key("FINAL_BILL", "bill.pdf"), Fixtures.head("application/pdf", 1))).isEmpty();
        assertThat(rule.check(Fixtures.key("ID_PROOF", "id.JPG"), Fixtures.head("image/jpeg", 1))).isEmpty();
        assertThat(rule.check(Fixtures.key("ID_PROOF", "id.png"), Fixtures.head("Image/PNG; charset=binary", 1))).isEmpty();
        assertThat(rule.check(Fixtures.key("ID_PROOF", "no-extension"), Fixtures.head("image/png", 1))).isEmpty();
    }

    @Test
    void anExecutableIsRejected() {
        assertThat(rule.check(Fixtures.key("ID_PROOF", "setup.exe"), Fixtures.head("application/x-msdownload", 1)))
                .contains("File type application/x-msdownload is not allowed; upload a PDF, JPEG or PNG");
    }

    @Test
    void anExecutableDisguisedAsAPdfIsRejectedByItsExtension() {
        assertThat(rule.check(Fixtures.key("ID_PROOF", "setup.exe"), Fixtures.head("application/pdf", 1)))
                .contains("Files ending in .exe are not allowed; upload a PDF, JPEG or PNG");
    }

    @Test
    void aMissingTypeIsRejected() {
        assertThat(rule.check(Fixtures.key("FINAL_BILL", "bill.pdf"), Fixtures.head(null, 1)))
                .contains("File type (none) is not allowed; upload a PDF, JPEG or PNG");
    }

    @Test
    void onlyConfiguredTypesCount() {
        ContentTypeRule pdfOnly = new ContentTypeRule(Set.of("application/pdf"));
        assertThat(pdfOnly.check(Fixtures.key("ID_PROOF", "id.png"), Fixtures.head("image/png", 1))).isPresent();
    }
}
