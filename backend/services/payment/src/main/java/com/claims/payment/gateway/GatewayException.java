package com.claims.payment.gateway;

/**
 * A failed gateway call.
 *
 * @param code e.g. TIMEOUT, NETWORK, HTTP_503, HTTP_409 (stored in payment_attempt.result_code)
 */
public class GatewayException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public GatewayException(String code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    /** Worth trying again (timeouts, network errors, 5xx); a 4xx will not change. */
    public boolean retryable() {
        return retryable;
    }
}
