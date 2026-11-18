package com.claims.gateway;

import com.claims.common.error.ApiError;
import com.claims.common.error.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GatewayExceptionHandler extends GlobalExceptionHandler {

    @Override
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        if (ex.getCause() instanceof InvalidFormatException ife && ife.getTargetType() == FailureMode.class) {
            return respond(HttpStatus.BAD_REQUEST, "mode must be NONE, FAIL_AUTHORIZE, FAIL_CAPTURE or SLOW", req);
        }
        return super.handleUnreadable(ex, req);
    }
}
