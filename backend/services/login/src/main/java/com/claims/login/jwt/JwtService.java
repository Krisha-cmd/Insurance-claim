package com.claims.login.jwt;

import com.claims.login.config.AppProperties;
import com.claims.login.user.User;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and verifies RS256 access tokens.
 * Claims: sub (user id), name, role, policyholderId (policyholders only), iss, iat, exp, jti.
 */
@Service
public class JwtService {

    private final JwtKeys keys;
    private final String issuer;
    private final Duration ttl;
    private final Clock clock;
    private final RSASSASigner signer;
    private final RSASSAVerifier verifier;

    @Autowired
    public JwtService(JwtKeys keys, AppProperties props, Clock clock) {
        this(keys, props.jwt().issuer(), props.jwt().ttl(), clock);
    }

    JwtService(JwtKeys keys, String issuer, Duration ttl, Clock clock) {
        this.keys = keys;
        this.issuer = issuer;
        this.ttl = ttl;
        this.clock = clock;
        try {
            this.signer = new RSASSASigner(keys.signingKey());
            this.verifier = new RSASSAVerifier(keys.publicJwk());
        } catch (JOSEException e) {
            throw new IllegalStateException("Invalid JWT key", e);
        }
    }

    public String issue(User user) {
        Instant now = clock.instant();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(user.getId().toString())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)))
                .claim("name", user.getFullName())
                .claim("role", user.getRole().name());
        if (user.getPolicyholderId() != null) {
            claims.claim("policyholderId", user.getPolicyholderId().toString());
        }

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(keys.publicJwk().getKeyID())
                .build();
        SignedJWT jwt = new SignedJWT(header, claims.build());
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign the token", e);
        }
        return jwt.serialize();
    }

    public TokenClaims verify(String token) throws InvalidTokenException {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
                throw new InvalidTokenException("Unexpected algorithm");
            }
            if (!jwt.verify(verifier)) {
                throw new InvalidTokenException("Bad signature");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (!issuer.equals(claims.getIssuer())) {
                throw new InvalidTokenException("Wrong issuer");
            }
            Date exp = claims.getExpirationTime();
            if (exp == null || !exp.toInstant().isAfter(clock.instant())) {
                throw new InvalidTokenException("Token expired");
            }
            String policyholderId = claims.getStringClaim("policyholderId");
            return new TokenClaims(
                    UUID.fromString(claims.getSubject()),
                    claims.getStringClaim("name"),
                    User.Role.valueOf(claims.getStringClaim("role")),
                    policyholderId == null ? null : UUID.fromString(policyholderId));
        } catch (ParseException | JOSEException | IllegalArgumentException | NullPointerException e) {
            throw new InvalidTokenException("Malformed token", e);
        }
    }
}
