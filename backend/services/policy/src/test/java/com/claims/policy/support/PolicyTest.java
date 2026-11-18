package com.claims.policy.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Real context + Postgres + an in-process Claim Intake; identical everywhere so the context is shared. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@Import(PolicyTestConfig.class)
@TestPropertySource(properties = {
        "claims.grpc.claim-target=claim-intake",
        "claims.grpc.server.in-process-name=policy-under-test",
        "claims.policy.participant.sweep-interval=PT1H",
        "claims.events.kafka-bootstrap=",
})
public @interface PolicyTest {
}
