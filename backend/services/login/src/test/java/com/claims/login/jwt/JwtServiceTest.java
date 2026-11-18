package com.claims.login.jwt;

import com.claims.login.user.User;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private final Instant now = Instant.parse("2026-11-16T10:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final JwtKeys keys = TestKeys.generated();
    private final JwtService jwt = new JwtService(keys, "claims-login", Duration.ofMinutes(60), clock);

    private final User patient = new User(UUID.randomUUID(), "patient1", "p@example.com", "x",
            User.Role.POLICYHOLDER, UUID.randomUUID(), "Asha Verma");
    private final User adjuster = new User(UUID.randomUUID(), "adjuster1", "a@example.com", "x",
            User.Role.ADJUSTER, null, "Rohan Mehta");

    @Test
    void issuesRs256TokenWithTheExpectedClaims() throws Exception {
        SignedJWT parsed = SignedJWT.parse(jwt.issue(patient));

        assertThat(parsed.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(parsed.getHeader().getKeyID()).isEqualTo(keys.publicJwk().getKeyID());
        var c = parsed.getJWTClaimsSet();
        assertThat(c.getSubject()).isEqualTo(patient.getId().toString());
        assertThat(c.getIssuer()).isEqualTo("claims-login");
        assertThat(c.getStringClaim("name")).isEqualTo("Asha Verma");
        assertThat(c.getStringClaim("role")).isEqualTo("POLICYHOLDER");
        assertThat(c.getStringClaim("policyholderId")).isEqualTo(patient.getPolicyholderId().toString());
        assertThat(c.getIssueTime().toInstant()).isEqualTo(now);
        assertThat(c.getExpirationTime().toInstant()).isEqualTo(now.plus(Duration.ofMinutes(60)));
    }

    @Test
    void adjusterTokenHasNoPolicyholderId() throws Exception {
        var c = SignedJWT.parse(jwt.issue(adjuster)).getJWTClaimsSet();
        assertThat(c.getClaims()).doesNotContainKey("policyholderId");
    }

    @Test
    void verifyReturnsTheClaims() throws Exception {
        TokenClaims claims = jwt.verify(jwt.issue(patient));
        assertThat(claims).isEqualTo(new TokenClaims(patient.getId(), "Asha Verma",
                User.Role.POLICYHOLDER, patient.getPolicyholderId()));
    }

    @Test
    void rejectsExpiredToken() {
        String token = jwt.issue(patient);
        JwtService later = new JwtService(keys, "claims-login", Duration.ofMinutes(60),
                Clock.fixed(now.plus(Duration.ofMinutes(60)), ZoneOffset.UTC));
        assertThatThrownBy(() -> later.verify(token)).isInstanceOf(InvalidTokenException.class).hasMessage("Token expired");
    }

    @Test
    void rejectsTokenSignedByAnotherKey() {
        String foreign = TestKeys.jwtService(clock).issue(patient);
        assertThatThrownBy(() -> jwt.verify(foreign)).isInstanceOf(InvalidTokenException.class).hasMessage("Bad signature");
    }

    @Test
    void rejectsTamperedPayload() {
        String[] parts = jwt.issue(adjuster).split("\\.");
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(parts[1])).replace("ADJUSTER", "POLICYHOLDER").getBytes());
        assertThatThrownBy(() -> jwt.verify(parts[0] + "." + forged + "." + parts[2]))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsWrongIssuer() {
        String other = new JwtService(keys, "someone-else", Duration.ofMinutes(60), clock).issue(patient);
        assertThatThrownBy(() -> jwt.verify(other)).isInstanceOf(InvalidTokenException.class).hasMessage("Wrong issuer");
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> jwt.verify("not-a-jwt")).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void loadsKeysFromPemLikeTheJwtKeysSecret() throws Exception {
        KeyPair pair = rsaPair();
        JwtKeys fromPem = new JwtKeys(pem("PRIVATE KEY", pair.getPrivate().getEncoded()),
                pem("PUBLIC KEY", pair.getPublic().getEncoded()), false);
        JwtService svc = new JwtService(fromPem, "claims-login", Duration.ofMinutes(60), clock);

        assertThat(svc.verify(svc.issue(patient)).userId()).isEqualTo(patient.getId());
        assertThat(fromPem.publicJwk().isPrivate()).isFalse();
    }

    @Test
    void acceptsEscapedNewlinesInPem() throws Exception {
        KeyPair pair = rsaPair();
        String priv = pem("PRIVATE KEY", pair.getPrivate().getEncoded()).replace("\n", "\\n");
        String pub = pem("PUBLIC KEY", pair.getPublic().getEncoded()).replace("\n", "\\n");
        assertThat(new JwtKeys(priv, pub, false).publicJwk()).isNotNull();
    }

    @Test
    void rejectsMismatchedKeyPair() throws Exception {
        String priv = pem("PRIVATE KEY", rsaPair().getPrivate().getEncoded());
        String pub = pem("PUBLIC KEY", rsaPair().getPublic().getEncoded());
        assertThatThrownBy(() -> new JwtKeys(priv, pub, false)).hasMessageContaining("not a matching key pair");
    }

    @Test
    void refusesToStartWithoutKeysOutsideLocal() {
        assertThatThrownBy(() -> new JwtKeys(null, null, false)).hasMessageContaining("must be set");
        assertThatThrownBy(() -> new JwtKeys("x", null, true)).hasMessageContaining("Set both");
    }

    private static KeyPair rsaPair() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }
}
