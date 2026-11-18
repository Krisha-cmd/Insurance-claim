package com.claims.login.auth;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** The seed migration must contain BCrypt (strength 10) hashes of "password", never plain text. */
class PasswordHashTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    private List<String> seededHashes() throws Exception {
        String sql = new ClassPathResource("db/migration/V2__seed_users.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        return Pattern.compile("\\$2[aby]\\$\\d\\d\\$[./A-Za-z0-9]{53}").matcher(sql).results()
                .map(m -> m.group()).toList();
    }

    @Test
    void seedHasOneStrength10HashPerUser() throws Exception {
        assertThat(seededHashes()).hasSize(2).allMatch(h -> h.startsWith("$2a$10$"));
    }

    @Test
    void seededHashesMatchTheDemoPasswordOnly() throws Exception {
        for (String hash : seededHashes()) {
            assertThat(encoder.matches("password", hash)).isTrue();
            assertThat(encoder.matches("Password", hash)).isFalse();
            assertThat(encoder.matches("", hash)).isFalse();
        }
    }

    @Test
    void seedDoesNotContainThePlainPasswordAsAValue() throws Exception {
        String sql = new ClassPathResource("db/migration/V2__seed_users.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(sql).doesNotContain("'password'");
    }
}
