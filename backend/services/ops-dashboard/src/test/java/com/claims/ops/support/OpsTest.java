package com.claims.ops.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@Import(OpsTestConfig.class)
@TestPropertySource(properties = {
        "claims.grpc.claim-target=claim-intake",
        "claims.ops.chaos-timeout=1s",
        "claims.events.kafka-bootstrap=",
})
public @interface OpsTest {
}
