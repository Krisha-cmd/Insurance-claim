package com.claims.payment.gateway;

/** A successful gateway answer: the authId (authorize), the capture reference (capture) or the authId (void). */
public record GatewayResponse(String reference) {
}
