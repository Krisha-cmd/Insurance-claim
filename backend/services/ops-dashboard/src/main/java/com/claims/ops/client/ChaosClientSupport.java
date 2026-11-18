package com.claims.ops.client;

import com.claims.ops.config.OpsProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

final class ChaosClientSupport {

    private ChaosClientSupport() {
    }

    static RestClient restClient(String baseUrl, OpsProperties props) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        int ms = (int) props.chaosTimeout().toMillis();
        f.setConnectTimeout(ms);
        f.setReadTimeout(ms);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(f).build();
    }
}
