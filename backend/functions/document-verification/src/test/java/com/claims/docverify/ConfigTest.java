package com.claims.docverify;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigTest {

    @Test
    void readsTheTerraformEnvironment() {
        Config c = Config.from(Map.of("RESULTS_QUEUE_URL", "https://sqs/q", "ALLOWED_TYPES", " application/PDF , image/png ",
                "MAX_SIZE_MB", "5", "DOCUMENTS_BUCKET", "docs", "AWS_REGION", "eu-west-1"));
        assertThat(c.allowedTypes()).containsExactlyInAnyOrder("application/pdf", "image/png");
        assertThat(c.maxSizeBytes()).isEqualTo(5L * 1024 * 1024);
        assertThat(c.documentsBucket()).isEqualTo("docs");
        assertThat(c.region()).isEqualTo("eu-west-1");
    }

    @Test
    void defaultsAndTheRequiredQueue() {
        Config c = Config.from(Map.of("RESULTS_QUEUE_URL", "q"));
        assertThat(c.allowedTypes()).containsExactlyInAnyOrder("application/pdf", "image/jpeg", "image/png");
        assertThat(c.maxSizeMb()).isEqualTo(10);
        assertThat(c.documentsBucket()).isNull();
        assertThatThrownBy(() -> Config.from(Map.of())).hasMessage("RESULTS_QUEUE_URL is not set");
    }
}
