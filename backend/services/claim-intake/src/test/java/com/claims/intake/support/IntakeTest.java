package com.claims.intake.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Real context + Postgres + LocalStack + in-process fakes. Identical on every class, so the context is shared. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntakeTestConfig.class)
@TestPropertySource(properties = {
        "claims.grpc.policy-target=policy",
        "claims.grpc.eligibility-target=eligibility",
        "claims.grpc.assessment-target=assessment",
        "claims.grpc.server.in-process-name=claim-intake-under-test",
        "claims.intake.results.wait-seconds=1",
        "claims.intake.workflow.check-timeout=2s",
        "claims.intake.workflow.sweep-interval=PT1H",
        "claims.intake.participant.sweep-interval=PT1H",
        "claims.events.kafka-bootstrap=",
})
public @interface IntakeTest {
}
