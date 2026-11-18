package com.claims.login.auth;

import com.claims.common.error.ApiException;
import com.claims.login.auth.AuthDtos.LoginResponse;
import com.claims.login.auth.AuthDtos.MeResponse;
import com.claims.login.auth.AuthDtos.UserView;
import com.claims.login.jwt.JwtService;
import com.claims.login.user.LoginAttempt;
import com.claims.login.user.LoginAttemptRepository;
import com.claims.login.user.RefreshTokenRepository;
import com.claims.login.user.User;
import com.claims.login.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class AuthService {

    static final String INVALID_CREDENTIALS = "Invalid username or password";
    static final String ACCOUNT_LOCKED = "Account locked. Try again in 15 minutes.";

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final LoginAttemptRepository attempts;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LockoutPolicy lockout;
    private final Clock clock;

    /** Compared against when the username is unknown, so both cases take the same time. */
    private final String dummyHash;

    public AuthService(UserRepository users, LoginAttemptRepository attempts, RefreshTokenRepository refreshTokens,
                       PasswordEncoder passwordEncoder, JwtService jwtService, LockoutPolicy lockout, Clock clock) {
        this.users = users;
        this.attempts = attempts;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.lockout = lockout;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Every attempt is recorded, including the failed ones, so the transaction must not roll back
     * when we answer 401/423.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public LoginResponse login(String username, String password, String ipAddress) {
        Instant now = clock.instant();
        User user = users.findByUsername(username).orElse(null);

        if (user == null) {
            passwordEncoder.matches(password, dummyHash);
            attempts.save(new LoginAttempt(null, username, false, ipAddress, now));
            log.info("Login failed: unknown username");
            throw new ApiException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS);
        }

        if (user.isLocked()) {
            Instant lastFailure = attempts.findFirstByUserIdAndSuccessFalseOrderByAttemptedAtDesc(user.getId())
                    .map(LoginAttempt::getAttemptedAt).orElse(null);
            if (lockout.lockExpired(lastFailure, now)) {
                user.unlock();
                log.info("Account {} unlocked after the lockout window", user.getUsername());
            } else {
                attempts.save(new LoginAttempt(user.getId(), username, false, ipAddress, now));
                log.info("Login refused: account {} is locked", user.getUsername());
                throw new ApiException(HttpStatus.LOCKED, ACCOUNT_LOCKED);
            }
        }

        if (passwordEncoder.matches(password, user.getPasswordHash())) {
            attempts.save(new LoginAttempt(user.getId(), username, true, ipAddress, now));
            user.recordLogin(now);
            log.info("Login succeeded for {}", user.getUsername());
            return new LoginResponse(jwtService.issue(user),
                    new UserView(user.getId(), user.getFullName(), user.getRole()));
        }

        attempts.save(new LoginAttempt(user.getId(), username, false, ipAddress, now));
        Instant lastSuccess = attempts.findFirstByUserIdAndSuccessTrueOrderByAttemptedAtDesc(user.getId())
                .map(LoginAttempt::getAttemptedAt).orElse(null);
        long recentFailures = attempts.countByUserIdAndSuccessFalseAndAttemptedAtAfter(
                user.getId(), lockout.countFailuresAfter(now, lastSuccess));

        if (lockout.shouldLock(recentFailures)) {
            user.lock();
            log.warn("Account {} locked after {} failed attempts", user.getUsername(), recentFailures);
            throw new ApiException(HttpStatus.LOCKED, ACCOUNT_LOCKED);
        }
        log.info("Login failed for {} ({} recent failures)", user.getUsername(), recentFailures);
        throw new ApiException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS);
    }

    @Transactional(readOnly = true)
    public MeResponse me(UUID userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Your session has expired. Please sign in again."));
        return new MeResponse(user.getId(), user.getFullName(), user.getRole(), user.getPolicyholderId());
    }

    /** Revokes the user's refresh tokens. The access token itself stays valid until it expires. */
    @Transactional
    public void logout(UUID userId) {
        int revoked = refreshTokens.revokeAllForUser(userId);
        log.info("Logout for user {} ({} refresh tokens revoked)", userId, revoked);
    }
}
