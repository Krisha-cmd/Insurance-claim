package com.claims.ops.client;

import com.claims.ops.config.OpsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** Approval's POST/GET /internal/chaos {mode: NONE | CRASH_BEFORE_PRECOMMIT | CRASH_AFTER_PRECOMMIT}. */
@Component
public class ApprovalChaosClient {

    private final RestClient http;

    public ApprovalChaosClient(OpsProperties props) {
        this.http = ChaosClientSupport.restClient(props.approvalUrl(), props);
    }

    /** @return the mode Approval now reports */
    public String set(String mode) {
        JsonNode res = http.post().uri("/internal/chaos").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("mode", mode)).retrieve().body(JsonNode.class);
        return res == null ? mode : res.path("mode").asText(mode);
    }

    public String get() {
        JsonNode res = http.get().uri("/internal/chaos").retrieve().body(JsonNode.class);
        return res == null ? null : res.path("mode").asText(null);
    }
}
