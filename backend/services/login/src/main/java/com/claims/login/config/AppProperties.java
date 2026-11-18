package com.claims.login.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties("app")
public record AppProperties(Jwt jwt, Cors cors, Lockout lockout) {

    /**
     * @param privateKey        PKCS#8 PEM ("BEGIN PRIVATE KEY"), from JWT_PRIVATE_KEY
     * @param publicKey         X.509 PEM ("BEGIN PUBLIC KEY"), from JWT_PUBLIC_KEY
     * @param generateIfMissing true only in the `local` profile
     */
    public record Jwt(String issuer, Duration ttl, String privateKey, String publicKey, boolean generateIfMissing) {
    }

    public record Cors(List<String> allowedOrigins) {
    }

    /** {@code maxFailures} failed attempts within {@code window} lock the account for {@code window}. */
    public record Lockout(int maxFailures, Duration window) {
    }
}
