package com.claims.assessment.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Real context + Postgres + in-process Policy and Claim Intake; identical everywhere so the context is shared. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@Import(AssessmentTestConfig.class)
@TestPropertySource(properties = {
        "claims.grpc.policy-target=policy",
        "claims.grpc.claim-target=claim-intake",
        "claims.grpc.server.in-process-name=assessment-under-test",
        "claims.events.kafka-bootstrap=",
})
public @interface AssessmentTest {
}
