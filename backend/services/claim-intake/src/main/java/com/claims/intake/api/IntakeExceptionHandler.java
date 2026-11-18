package com.claims.intake.api;

import com.claims.common.error.ApiError;
import com.claims.common.error.GlobalExceptionHandler;
import com.claims.intake.domain.DocType;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDate;

@RestControllerAdvice
public class IntakeExceptionHandler extends GlobalExceptionHandler {

    @Override
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        if (ex.getCause() instanceof InvalidFormatException ife) {
            if (ife.getTargetType() == DocType.class) {
                return respond(HttpStatus.BAD_REQUEST, "Unknown document type.", req);
            }
            if (ife.getTargetType() == LocalDate.class) {
                return respond(HttpStatus.BAD_REQUEST, "Dates must be in the format yyyy-MM-dd.", req);
            }
        }
        return super.handleUnreadable(ex, req);
    }
}
