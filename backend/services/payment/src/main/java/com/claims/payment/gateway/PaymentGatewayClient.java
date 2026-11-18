package com.claims.payment.gateway;

import java.math.BigDecimal;
import java.util.UUID;

/** The external payment gateway. Every call is idempotent on the gateway's side. */
public interface PaymentGatewayClient {

    /** Holds {@code amount} for {@code paymentId}; returns the authId. Same paymentId → same authorization. */
    GatewayResponse authorize(UUID paymentId, BigDecimal amount);

    /** Moves the held money; returns the capture reference. Same authId → same reference. */
    GatewayResponse capture(String authId);

    /** Releases the hold. */
    GatewayResponse voidAuth(String authId);
}
