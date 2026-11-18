package com.claims.docverify;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import static org.assertj.core.api.Assertions.assertThat;

class NonEmptyRuleTest {

    private final NonEmptyRule rule = new NonEmptyRule();
    private final ObjectKey key = Fixtures.key("FINAL_BILL", "bill.pdf");

    @Test
    void anyContentPasses() {
        assertThat(rule.check(key, Fixtures.head("application/pdf", 1))).isEmpty();
    }

    @Test
    void zeroOrUnknownSizeIsRejected() {
        assertThat(rule.check(key, Fixtures.head("application/pdf", 0))).contains("The file is empty");
        assertThat(rule.check(key, HeadObjectResponse.builder().contentType("application/pdf").build())).contains("The file is empty");
    }
}
