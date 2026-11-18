package com.claims.login.jwt;

import com.claims.login.config.AppProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * The RS256 signing key pair. On EKS both halves come from the `jwt-keys` Secret
 * (JWT_PRIVATE_KEY / JWT_PUBLIC_KEY). Only the `local` profile may generate a throw-away pair.
 */
@Component
public class JwtKeys {

    private static final Logger log = LoggerFactory.getLogger(JwtKeys.class);

    private final RSAKey rsaKey;

    @Autowired
    public JwtKeys(AppProperties props) {
        this(props.jwt().privateKey(), props.jwt().publicKey(), props.jwt().generateIfMissing());
    }

    JwtKeys(String privatePem, String publicPem, boolean generateIfMissing) {
        boolean hasPrivate = privatePem != null && !privatePem.isBlank();
        boolean hasPublic = publicPem != null && !publicPem.isBlank();

        RSAPublicKey publicKey;
        RSAPrivateKey privateKey;
        if (hasPrivate && hasPublic) {
            privateKey = parsePrivateKey(privatePem);
            publicKey = parsePublicKey(publicPem);
            if (privateKey instanceof RSAPrivateCrtKey crt && !crt.getModulus().equals(publicKey.getModulus())) {
                throw new IllegalStateException("JWT_PRIVATE_KEY and JWT_PUBLIC_KEY are not a matching key pair.");
            }
        } else if (hasPrivate || hasPublic) {
            throw new IllegalStateException("Set both JWT_PRIVATE_KEY and JWT_PUBLIC_KEY, or neither (local profile only).");
        } else if (generateIfMissing) {
            log.warn("JWT_PRIVATE_KEY / JWT_PUBLIC_KEY not set: generated an in-memory RSA key pair. "
                    + "Tokens will not survive a restart. Never use this outside local development.");
            KeyPair pair = generate();
            privateKey = (RSAPrivateKey) pair.getPrivate();
            publicKey = (RSAPublicKey) pair.getPublic();
        } else {
            throw new IllegalStateException("JWT_PRIVATE_KEY and JWT_PUBLIC_KEY must be set (PEM). "
                    + "On EKS they come from the jwt-keys Secret.");
        }

        RSAKey withoutKid = new RSAKey.Builder(publicKey).privateKey(privateKey).build();
        try {
            this.rsaKey = new RSAKey.Builder(withoutKid)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyID(withoutKid.computeThumbprint().toString())
                    .build();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not compute the JWT key id", e);
        }
    }

    /** Public + private key. Never expose this; use {@link #publicJwk()} for JWKS. */
    RSAKey signingKey() {
        return rsaKey;
    }

    public RSAKey publicJwk() {
        return rsaKey.toPublicJWK();
    }

    static RSAPrivateKey parsePrivateKey(String pem) {
        if (pem.contains("BEGIN RSA PRIVATE KEY")) {
            throw new IllegalStateException("JWT_PRIVATE_KEY is PKCS#1; provide PKCS#8 (\"BEGIN PRIVATE KEY\").");
        }
        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(decodePem(pem, "PRIVATE KEY")));
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
            throw new IllegalStateException("JWT_PRIVATE_KEY is not a valid PKCS#8 RSA private key.", e);
        }
    }

    static RSAPublicKey parsePublicKey(String pem) {
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(decodePem(pem, "PUBLIC KEY")));
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
            throw new IllegalStateException("JWT_PUBLIC_KEY is not a valid X.509 RSA public key.", e);
        }
    }

    /** Accepts real newlines or literal "\n" (handy for `docker run -e`). */
    private static byte[] decodePem(String pem, String type) {
        String body = pem.replace("\\n", "\n")
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not generate an RSA key pair", e);
        }
    }
}
