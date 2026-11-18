package com.claims.payment.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.UUID;

/**
 * The gateway over REST (the gateway simulator in this project). Timeouts come from the RestClient's request
 * factory (GatewayClientConfig: connect 1 s, read 3 s). Every failure becomes a {@link GatewayException}, so the
 * participant can record it and vote NO instead of failing the whole prepare.
 */
public class HttpPaymentGatewayClient implements PaymentGatewayClient {

    private final RestClient http;
    private final ObjectMapper json;

    public HttpPaymentGatewayClient(RestClient http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public GatewayResponse authorize(UUID paymentId, BigDecimal amount) {
        return new GatewayResponse(post("/authorize", Map.of("paymentId", paymentId, "amount", amount), "authId"));
    }

    @Override
    public GatewayResponse capture(String authId) {
        return new GatewayResponse(post("/capture", Map.of("authId", authId), "captureRef"));
    }

    @Override
    public GatewayResponse voidAuth(String authId) {
        post("/void", Map.of("authId", authId), null);
        return new GatewayResponse(authId);
    }

    private String post(String path, Map<String, ?> body, String field) {
        try {
            JsonNode res = http.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().body(JsonNode.class);
            if (field == null) return null;
            if (res == null || !res.hasNonNull(field)) {
                throw new GatewayException("BAD_RESPONSE", "Gateway answer to " + path + " has no " + field, true, null);
            }
            return res.get(field).asText();
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            throw new GatewayException("HTTP_" + status.value(), message(e), status.is5xxServerError() || status.value() == 429, e);
        } catch (RestClientException e) {
            // ResourceAccessException (connect/read), or a timeout while the body was being read, which Spring
            // reports as "Error while extracting response". Any of them may succeed on a retry.
            boolean timeout = isTimeout(e);
            throw new GatewayException(timeout ? "TIMEOUT" : "NETWORK",
                    timeout ? "The payment gateway did not answer in time" : "Cannot reach the payment gateway: " + e.getMessage(), true, e);
        }
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException || t instanceof HttpTimeoutException) return true;
        }
        return false;
    }

    /** The gateway's own message (ApiError body) when there is one. */
    private String message(RestClientResponseException e) {
        try {
            JsonNode body = json.readTree(e.getResponseBodyAsString());
            if (body != null && body.hasNonNull("message")) return body.get("message").asText();
        } catch (Exception ignored) {
            // not JSON
        }
        return "Gateway answered " + e.getStatusCode().value();
    }
}
