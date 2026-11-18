package com.claims.payment.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Real context + Postgres + WireMock gateway + in-process Claim Intake; identical everywhere so the context is shared. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentTestConfig.class)
@TestPropertySource(properties = {
        "claims.grpc.claim-target=claim-intake",
        "claims.grpc.server.in-process-name=payment-under-test",
        "claims.payment.gateway.read-timeout=1s",
        "claims.payment.gateway.capture-backoff=50ms",
        "claims.payment.participant.sweep-interval=PT1H",
        "claims.payment.recovery-interval=PT1H",
        "claims.events.kafka-bootstrap=",
})
public @interface PaymentTest {
}
