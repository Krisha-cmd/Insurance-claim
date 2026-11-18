package com.claims.docverify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.claims.docverify.Fixtures.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S3EventParserTest {

    private final S3EventParser parser = new S3EventParser(new ObjectMapper());

    @Test
    void readsTheObjectFromARealPutEvent() {
        List<ObjectKey> keys = parser.parse(event("put-pdf.json"));

        assertThat(keys).singleElement().satisfies(k -> {
            assertThat(k.bucket()).isEqualTo(Fixtures.BUCKET);
            assertThat(k.key()).isEqualTo("claims/" + Fixtures.CLAIM + "/FINAL_BILL/" + Fixtures.BILL + "-final_bill.pdf");
            assertThat(k.claimId()).isEqualTo(Fixtures.CLAIM);
            assertThat(k.docType()).isEqualTo("FINAL_BILL");
            assertThat(k.documentId()).isEqualTo(Fixtures.BILL);
            assertThat(k.fileName()).isEqualTo("final_bill.pdf");
            assertThat(k.eventSize()).isEqualTo(48213L);
            assertThat(k.eTag()).isEqualTo("9b2cf535f27731c974343645a3985328");
            assertThat(k.wellFormed()).isTrue();
        });
    }

    @Test
    void decodesUrlEncodedKeys() {
        ObjectKey k = parser.parse(event("put-encoded-key.json")).getFirst();
        assertThat(k.fileName()).isEqualTo("blood test(1).pdf");
        assertThat(k.documentId()).isEqualTo(UUID.fromString("e2f9a6b4-7c1d-4a8e-9f3b-6c5d4e3f2a74"));
        assertThat(k.extension()).isEqualTo("pdf");
    }

    @Test
    void oneMessageCanHoldSeveralObjects() {
        assertThat(parser.parse(event("put-two-objects.json"))).extracting(ObjectKey::docType)
                .containsExactly("PRESCRIPTION", "FINAL_BILL");
    }

    @Test
    void theS3TestEventAndRemovalsYieldNothing() {
        assertThat(parser.parse(event("test-event.json"))).isEmpty();
        assertThat(parser.parse(event("object-removed.json"))).isEmpty();
    }

    @Test
    void aKeyOutsideTheClaimsLayoutIsKeptButNotWellFormed() {
        ObjectKey k = parser.parse(event("put-bad-key.json")).getFirst();
        assertThat(k.wellFormed()).isFalse();
        assertThat(k.documentId()).isNull();
        assertThat(k.key()).isEqualTo("uploads/2026/random-file.pdf");
    }

    @Test
    void aBodyThatIsNotAnS3EventIsRejected() {
        assertThatThrownBy(() -> parser.parse("not json")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("{\"hello\":1}")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Records");
        assertThatThrownBy(() -> parser.parse("[]")).isInstanceOf(IllegalArgumentException.class);
    }
}
