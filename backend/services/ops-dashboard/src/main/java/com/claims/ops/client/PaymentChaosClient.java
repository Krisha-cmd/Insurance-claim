package com.claims.ops.client;

import com.claims.ops.config.OpsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** Payment's POST/GET /internal/chaos {mode, delayMs}, forwarded to the gateway simulator. */
@Component
public class PaymentChaosClient {

    public record Mode(String mode, long delayMs) {
    }

    private final RestClient http;

    public PaymentChaosClient(OpsProperties props) {
        this.http = ChaosClientSupport.restClient(props.paymentUrl(), props);
    }

    public Mode set(String mode, long delayMs) {
        return read(http.post().uri("/internal/chaos").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("mode", mode, "delayMs", delayMs)).retrieve().body(JsonNode.class), mode);
    }

    public Mode get() {
        return read(http.get().uri("/internal/chaos").retrieve().body(JsonNode.class), null);
    }

    private static Mode read(JsonNode n, String fallback) {
        if (n == null) return new Mode(fallback, 0);
        return new Mode(n.path("mode").asText(fallback), n.path("delayMs").asLong(0));
    }
}
