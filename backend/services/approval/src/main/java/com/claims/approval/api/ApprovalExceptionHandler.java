package com.claims.approval.api;

import com.claims.approval.domain.Decision;
import com.claims.approval.service.ChaosMode;
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
public class ApprovalExceptionHandler extends GlobalExceptionHandler {

    /** Names the allowed values when an enum field has an unknown value. */
    @Override
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        if (ex.getCause() instanceof InvalidFormatException ife) {
            if (ife.getTargetType() == Decision.class) {
                return respond(HttpStatus.BAD_REQUEST, "Decision must be APPROVED or REJECTED.", req);
            }
            if (ife.getTargetType() == ChaosMode.class) {
                return respond(HttpStatus.BAD_REQUEST, "mode must be NONE, CRASH_BEFORE_PRECOMMIT or CRASH_AFTER_PRECOMMIT.", req);
            }
        }
        return super.handleUnreadable(ex, req);
    }
}
