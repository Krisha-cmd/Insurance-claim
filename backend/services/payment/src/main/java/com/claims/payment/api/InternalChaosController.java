package com.claims.payment.api;

import com.claims.common.error.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Forwards to the gateway simulator's /chaos (used by the Ops Dashboard backend). Cluster-internal: the
 * ingress does not route /internal, and Payment has no public API at all.
 */
@RestController
@RequestMapping("/internal/chaos")
public class InternalChaosController {

    public record ChaosRequest(
            @NotBlank @Pattern(regexp = "NONE|FAIL_AUTHORIZE|FAIL_CAPTURE|SLOW",
                    message = "mode must be NONE, FAIL_AUTHORIZE, FAIL_CAPTURE or SLOW") String mode,
            @PositiveOrZero Long delayMs) {
    }

    private final RestClient gateway;

    public InternalChaosController(RestClient gatewayRestClient) {
        this.gateway = gatewayRestClient;
    }

    @PostMapping
    public JsonNode set(@Valid @RequestBody ChaosRequest body) {
        Map<String, Object> forward = new LinkedHashMap<>();
        forward.put("mode", body.mode());
        forward.put("delayMs", body.delayMs() == null ? 0 : body.delayMs());
        try {
            return gateway.post().uri("/chaos").contentType(MediaType.APPLICATION_JSON).body(forward).retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The payment gateway did not accept the chaos mode: " + e.getMessage());
        }
    }

    @GetMapping
    public JsonNode get() {
        try {
            return gateway.get().uri("/chaos").retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The payment gateway is not reachable: " + e.getMessage());
        }
    }
}
