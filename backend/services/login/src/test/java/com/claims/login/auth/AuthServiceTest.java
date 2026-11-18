package com.claims.login.auth;

import com.claims.common.error.ApiException;
import com.claims.login.auth.AuthDtos.LoginResponse;
import com.claims.login.jwt.JwtService;
import com.claims.login.jwt.TestKeys;
import com.claims.login.jwt.TokenClaims;
import com.claims.login.user.LoginAttempt;
import com.claims.login.user.LoginAttemptRepository;
import com.claims.login.user.RefreshTokenRepository;
import com.claims.login.user.User;
import com.claims.login.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Password check and lockout, with the repositories replaced by an in-memory list. */
class AuthServiceTest {

    private static final String IP = "10.0.0.1";

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4); // fast for tests
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-16T10:00:00Z"));
    private final List<LoginAttempt> attemptLog = new ArrayList<>();

    private User patient;
    private AuthService service;
    private JwtService jwt;

    @BeforeEach
    void setUp() {
        patient = new User(UUID.randomUUID(), "patient1", "p@example.com", encoder.encode("password"),
                User.Role.POLICYHOLDER, UUID.randomUUID(), "Asha Verma");

        UserRepository users = mock(UserRepository.class);
        when(users.findByUsername(anyString())).thenReturn(Optional.empty());
        when(users.findByUsername("patient1")).thenReturn(Optional.of(patient));

        LoginAttemptRepository attempts = mock(LoginAttemptRepository.class);
        when(attempts.save(any())).thenAnswer(inv -> {
            attemptLog.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(attempts.countByUserIdAndSuccessFalseAndAttemptedAtAfter(any(), any())).thenAnswer(inv ->
                attemptLog.stream().filter(a -> inv.getArgument(0).equals(a.getUserId()) && !a.isSuccess()
                        && a.getAttemptedAt().isAfter(inv.getArgument(1))).count());
        when(attempts.findFirstByUserIdAndSuccessTrueOrderByAttemptedAtDesc(any())).thenAnswer(inv ->
                latest(inv.getArgument(0), true));
        when(attempts.findFirstByUserIdAndSuccessFalseOrderByAttemptedAtDesc(any())).thenAnswer(inv ->
                latest(inv.getArgument(0), false));

        jwt = TestKeys.jwtService(clock);
        service = new AuthService(users, attempts, mock(RefreshTokenRepository.class), encoder, jwt,
                new LockoutPolicy(5, Duration.ofMinutes(15)), clock);
    }

    private Optional<LoginAttempt> latest(UUID userId, boolean success) {
        return attemptLog.stream()
                .filter(a -> userId.equals(a.getUserId()) && a.isSuccess() == success)
                .max(Comparator.comparing(LoginAttempt::getAttemptedAt));
    }

    private HttpStatus statusOf(String password) {
        try {
            service.login("patient1", password, IP);
            return HttpStatus.OK;
        } catch (ApiException e) {
            return e.getStatus();
        }
    }

    @Test
    void correctPasswordReturnsATokenForTheUser() throws Exception {
        LoginResponse res = service.login("patient1", "password", IP);

        assertThat(res.user().id()).isEqualTo(patient.getId());
        assertThat(res.user().name()).isEqualTo("Asha Verma");
        assertThat(res.user().role()).isEqualTo(User.Role.POLICYHOLDER);
        TokenClaims claims = jwt.verify(res.token());
        assertThat(claims.userId()).isEqualTo(patient.getId());
        assertThat(claims.policyholderId()).isEqualTo(patient.getPolicyholderId());
        assertThat(patient.getLastLoginAt()).isEqualTo(clock.instant());
        assertThat(attemptLog).singleElement().satisfies(a -> {
            assertThat(a.isSuccess()).isTrue();
            assertThat(a.getIpAddress()).isEqualTo(IP);
        });
    }

    @Test
    void wrongPasswordAndUnknownUserGiveTheSameMessage() {
        assertThatThrownBy(() -> service.login("patient1", "nope", IP))
                .isInstanceOf(ApiException.class)
                .hasMessage("Invalid username or password")
                .extracting("status").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThatThrownBy(() -> service.login("nobody", "password", IP))
                .isInstanceOf(ApiException.class)
                .hasMessage("Invalid username or password");

        assertThat(attemptLog).hasSize(2).noneMatch(LoginAttempt::isSuccess);
        assertThat(attemptLog.get(1).getUserId()).isNull();
        assertThat(attemptLog.get(1).getUsername()).isEqualTo("nobody");
    }

    @Test
    void fifthFailureWithin15MinutesLocksTheAccount() {
        for (int i = 0; i < 4; i++) {
            assertThat(statusOf("wrong")).isEqualTo(HttpStatus.UNAUTHORIZED);
            clock.advance(Duration.ofMinutes(1));
        }
        assertThat(statusOf("wrong")).isEqualTo(HttpStatus.LOCKED);
        assertThat(patient.isLocked()).isTrue();

        // Even the right password is refused while locked, and the attempt is still recorded.
        assertThatThrownBy(() -> service.login("patient1", "password", IP))
                .hasMessage("Account locked. Try again in 15 minutes.");
        assertThat(attemptLog).hasSize(6);
    }

    @Test
    void failuresSpreadOverMoreThan15MinutesDoNotLock() {
        for (int i = 0; i < 6; i++) {
            assertThat(statusOf("wrong")).isEqualTo(HttpStatus.UNAUTHORIZED);
            clock.advance(Duration.ofMinutes(4));
        }
        assertThat(patient.isLocked()).isFalse();
    }

    @Test
    void aSuccessfulLoginResetsTheFailureCount() {
        for (int i = 0; i < 4; i++) statusOf("wrong");
        assertThat(statusOf("password")).isEqualTo(HttpStatus.OK);
        clock.advance(Duration.ofSeconds(1));
        assertThat(statusOf("wrong")).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(patient.isLocked()).isFalse();
    }

    @Test
    void lockExpires15MinutesAfterTheLastFailure() {
        for (int i = 0; i < 5; i++) statusOf("wrong");
        assertThat(patient.isLocked()).isTrue();

        clock.advance(Duration.ofMinutes(14));
        assertThat(statusOf("password")).isEqualTo(HttpStatus.LOCKED);

        clock.advance(Duration.ofMinutes(15)); // 15 minutes after the refused attempt above
        assertThat(statusOf("password")).isEqualTo(HttpStatus.OK);
        assertThat(patient.isLocked()).isFalse();
    }

    /** A clock the test can move forward. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) { this.now = start; }

        void advance(Duration d) { now = now.plus(d); }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
