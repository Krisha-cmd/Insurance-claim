package com.claims.login;

import com.claims.login.jwt.JwtService;
import com.claims.login.jwt.TokenClaims;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full application against a real PostgreSQL 16, with the Flyway migrations applied. */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability // tests disable metric exporters by default; we check /actuator/prometheus
@Testcontainers
class LoginIntegrationTest {

    static final String PATIENT_ID = "4e33233c-a255-4c75-963a-6809cd3b0cdd";
    static final String PATIENT_POLICYHOLDER_ID = "a2b56321-c8b8-4159-93d6-3cf613b3c556";
    static final String ADJUSTER_ID = "997bacd0-5b29-4554-b398-52e9f89bfa6c";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbc;

    private ResultActions login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new Credentials(username, password))));
    }

    private String tokenFor(String username) throws Exception {
        String body = login(username, "password").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("token").asText();
    }

    record Credentials(String username, String password) {
    }

    @Test
    void flywayMigratedAndSeededTheUsers() {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("select role from users where username = 'patient1'", String.class))
                .isEqualTo("POLICYHOLDER");
    }

    @Test
    void patientLogsIn() throws Exception {
        String body = login("patient1", "password")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(PATIENT_ID))
                .andExpect(jsonPath("$.user.name").value("Asha Verma"))
                .andExpect(jsonPath("$.user.role").value("POLICYHOLDER"))
                .andReturn().getResponse().getContentAsString();

        JsonNode json = mapper.readTree(body);
        assertThat(json.get("user").size()).as("user has exactly id, name, role").isEqualTo(3);
        TokenClaims claims = jwtService.verify(json.get("token").asText());
        assertThat(claims.userId()).hasToString(PATIENT_ID);
        assertThat(claims.role().name()).isEqualTo("POLICYHOLDER");
        assertThat(claims.policyholderId()).hasToString(PATIENT_POLICYHOLDER_ID);
    }

    @Test
    void adjusterLogsIn() throws Exception {
        String body = login("adjuster1", "password")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(ADJUSTER_ID))
                .andExpect(jsonPath("$.user.name").value("Rohan Mehta"))
                .andExpect(jsonPath("$.user.role").value("ADJUSTER"))
                .andReturn().getResponse().getContentAsString();

        TokenClaims claims = jwtService.verify(mapper.readTree(body).get("token").asText());
        assertThat(claims.role().name()).isEqualTo("ADJUSTER");
        assertThat(claims.policyholderId()).isNull();
    }

    @Test
    void wrongPasswordIs401WithTheApiErrorShape() throws Exception {
        login("adjuster1", "wrong")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid username or password"))
                .andExpect(jsonPath("$.path").value("/api/auth/login"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());

        login("no-such-user", "password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
    }

    @Test
    void missingFieldsAre400WithAReadableMessage() throws Exception {
        login("", "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Enter your password. Enter your username."));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The request body is missing or is not valid JSON."));
    }

    @Test
    void fiveFailuresLockTheAccountAndEveryAttemptIsRecorded() throws Exception {
        // A dedicated user so the other tests' accounts stay unlocked.
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into users (id, username, email, password_hash, role, full_name)
                select ?, 'locktest', 'locktest@example.com', password_hash, 'POLICYHOLDER', 'Lock Test'
                from users where username = 'patient1'""", id);

        for (int i = 1; i <= 4; i++) {
            login("locktest", "wrong-" + i).andExpect(status().isUnauthorized());
        }
        login("locktest", "wrong-5")
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.status").value(423))
                .andExpect(jsonPath("$.message").value("Account locked. Try again in 15 minutes."));
        login("locktest", "password").andExpect(status().isLocked());

        assertThat(jdbc.queryForObject("select status from users where id = ?", String.class, id)).isEqualTo("LOCKED");
        assertThat(jdbc.queryForObject("select count(*) from login_attempts where user_id = ? and not success",
                Integer.class, id)).isEqualTo(6);
    }

    @Test
    void meReturnsTheProfileWithAValidToken() throws Exception {
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor("patient1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(PATIENT_ID))
                .andExpect(jsonPath("$.name").value("Asha Verma"))
                .andExpect(jsonPath("$.role").value("POLICYHOLDER"))
                .andExpect(jsonPath("$.policyholderId").value(PATIENT_POLICYHOLDER_ID));

        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor("adjuster1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADJUSTER"))
                .andExpect(jsonPath("$.policyholderId").value(nullValue()));
    }

    @Test
    void meWithoutOrWithABadTokenIs401() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Authentication required. Please sign in."));

        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Your session has expired. Please sign in again."));
    }

    @Test
    void loginStillWorksWhenAStaleTokenIsSent() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer stale.token.value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new Credentials("patient1", "password"))))
                .andExpect(status().isOk());
    }

    @Test
    void logoutRevokesRefreshTokensAndReturns204() throws Exception {
        jdbc.update("insert into refresh_tokens (user_id, token_hash, expires_at) values (?::uuid, ?, now() + interval '1 day')",
                ADJUSTER_ID, "a".repeat(64));

        mvc.perform(post("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor("adjuster1")))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ?::uuid and not revoked",
                Integer.class, ADJUSTER_ID)).isZero();
        mvc.perform(post("/api/auth/logout")).andExpect(status().isUnauthorized());
    }

    @Test
    void publicEndpointsNeedNoToken() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
        mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());
    }

    @Test
    void corsAllowsTheFrontendOrigin() throws Exception {
        mvc.perform(options("/api/auth/login")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));

        mvc.perform(options("/api/auth/login")
                        .header(HttpHeaders.ORIGIN, "http://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }
}
