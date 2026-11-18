package com.claims.docverify;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KeyFormatRuleTest {

    private final KeyFormatRule rule = new KeyFormatRule();
    private final software.amazon.awssdk.services.s3.model.HeadObjectResponse head = Fixtures.head("application/pdf", 10);

    @Test
    void acceptsEveryKnownDocType() {
        for (String type : Config.DOC_TYPES) {
            assertThat(rule.check(Fixtures.key(type, "x.pdf"), head)).as(type).isEmpty();
        }
    }

    @Test
    void rejectsAnUnknownDocType() {
        assertThat(rule.check(Fixtures.key("SELFIE", "me.jpg"), head)).contains("Unknown document type SELFIE");
    }

    @Test
    void rejectsKeysOutsideTheLayout() {
        assertThat(rule.check(ObjectKey.of("b", "uploads/x.pdf", 1L, null), head)).isPresent();
        assertThat(rule.check(ObjectKey.of("b", "claims/not-a-uuid/FINAL_BILL/" + Fixtures.BILL + "-x.pdf", 1L, null), head))
                .contains("The object key is not claims/{claimId}/{docType}/{documentId}-{fileName}");
        assertThat(rule.check(ObjectKey.of("b", "claims/" + Fixtures.CLAIM + "/FINAL_BILL/x.pdf", 1L, null), head)).isPresent();
    }
}
