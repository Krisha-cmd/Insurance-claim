package com.claims.login.auth;

import com.claims.common.error.GlobalExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Uses the shared ApiError handlers from `common`. */
@RestControllerAdvice
public class LoginExceptionHandler extends GlobalExceptionHandler {
}
