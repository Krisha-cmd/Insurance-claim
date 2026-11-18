package com.claims.login.jwt;

import com.claims.login.user.User;

import java.util.UUID;

/** What a verified access token says about its holder. Used as the Spring Security principal. */
public record TokenClaims(UUID userId, String name, User.Role role, UUID policyholderId) {
}
