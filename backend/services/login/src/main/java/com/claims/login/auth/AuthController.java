package com.claims.login.auth;

import com.claims.login.auth.AuthDtos.LoginRequest;
import com.claims.login.auth.AuthDtos.LoginResponse;
import com.claims.login.auth.AuthDtos.MeResponse;
import com.claims.login.jwt.TokenClaims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        return authService.login(body.username().trim(), body.password(), request.getRemoteAddr());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal TokenClaims principal) {
        authService.logout(principal.userId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal TokenClaims principal) {
        return authService.me(principal.userId());
    }
}
