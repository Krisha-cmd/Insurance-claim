package com.claims.docverify;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SizeRuleTest {

    private static final long MB = 1024 * 1024;
    private final SizeRule rule = new SizeRule(10 * MB);
    private final ObjectKey key = Fixtures.key("DISCHARGE_SUMMARY", "scan.pdf");

    @Test
    void upToTheLimitPasses() {
        assertThat(rule.check(key, Fixtures.head("application/pdf", 10 * MB))).isEmpty();
        assertThat(rule.check(key, Fixtures.head("application/pdf", 1))).isEmpty();
    }

    @Test
    void overTheLimitIsRejected() {
        assertThat(rule.check(key, Fixtures.head("application/pdf", 10 * MB + 1))).contains("The file is 10.0 MB; the limit is 10 MB");
        assertThat(rule.check(key, Fixtures.head("application/pdf", 15 * MB))).contains("The file is 15.0 MB; the limit is 10 MB");
    }
}
