package com.claims.payment.config;

import com.claims.payment.gateway.HttpPaymentGatewayClient;
import com.claims.payment.gateway.PaymentGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** The REST client for the payment gateway, with hard timeouts (a slow gateway must not hold a 3PC phase). */
@Configuration
public class GatewayClientConfig {

    @Bean
    RestClient gatewayRestClient(PaymentProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) props.gateway().connectTimeout().toMillis());
        factory.setReadTimeout((int) props.gateway().readTimeout().toMillis());
        return RestClient.builder().baseUrl(props.gateway().baseUrl()).requestFactory(factory).build();
    }

    @Bean
    PaymentGatewayClient paymentGatewayClient(RestClient gatewayRestClient, ObjectMapper json) {
        return new HttpPaymentGatewayClient(gatewayRestClient, json);
    }
}
