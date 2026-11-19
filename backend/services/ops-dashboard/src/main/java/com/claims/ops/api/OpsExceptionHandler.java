package com.claims.ops.api;

import com.claims.common.error.ApiError;
import com.claims.common.error.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import software.amazon.awssdk.core.exception.SdkException;

@RestControllerAdvice
public class OpsExceptionHandler extends GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(OpsExceptionHandler.class);

    /**
     * DynamoDB unreachable, table missing or access denied: the analytics store is unavailable, which is a 503
     * the console can show, not a server bug.
     */
    @ExceptionHandler(SdkException.class)
    public ResponseEntity<ApiError> handleDynamo(SdkException ex, HttpServletRequest req) {
        log.warn("Analytics store call failed on {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of(HttpStatus.SERVICE_UNAVAILABLE.value(),
                        "The analytics store is not available right now. Please try again shortly.", req.getRequestURI()));
    }
}
