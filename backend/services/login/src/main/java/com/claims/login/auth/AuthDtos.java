package com.claims.login.auth;

import com.claims.login.user.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Request/response bodies. Shapes follow ENDPOINTS.auth in frontend/src/config/api.js. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank(message = "Enter your username.") @Size(max = 100, message = "Username is too long.") String username,
            @NotBlank(message = "Enter your password.") @Size(max = 200, message = "Password is too long.") String password) {
    }

    /** POST /api/auth/login → {token, user:{id, name, role}} */
    public record LoginResponse(String token, UserView user) {
    }

    public record UserView(UUID id, String name, User.Role role) {
    }

    /** GET /api/auth/me → {id, name, role, policyholderId} */
    public record MeResponse(UUID id, String name, User.Role role, UUID policyholderId) {
    }
}
