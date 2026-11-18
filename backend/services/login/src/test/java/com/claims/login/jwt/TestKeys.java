package com.claims.login.jwt;

import java.time.Clock;
import java.time.Duration;

/** Test helpers living in the jwt package so they can use package-private constructors. */
public final class TestKeys {

    private TestKeys() {
    }

    public static JwtKeys generated() {
        return new JwtKeys(null, null, true);
    }

    public static JwtService jwtService(Clock clock) {
        return new JwtService(generated(), "claims-login", Duration.ofMinutes(60), clock);
    }
}
