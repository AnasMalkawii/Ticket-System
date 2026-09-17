package com.mysecurity.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;


import java.time.Instant;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException e) {
        HttpStatus httpStatus = e.getHttpStatus();

        ErrorResponse errorResponse = ErrorResponse.of(e.getMessage(), httpStatus.value(), httpStatus.name(), Instant.now());

        return ResponseEntity.status(e.getHttpStatus()).body(errorResponse);

    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(AccessDeniedException e) {
        HttpStatus httpStatus = HttpStatus.FORBIDDEN;

        ErrorResponse errorResponse = ErrorResponse.of("Access denied", httpStatus.value(), httpStatus.name(), Instant.now());

        return ResponseEntity.status(httpStatus).body(errorResponse);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationExceptions(MethodArgumentNotValidException ex) {

        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Validation error");

        ErrorResponse errorResponse = ErrorResponse.of(message, HttpStatus.BAD_REQUEST.value(), HttpStatus.BAD_REQUEST.name(), Instant.now());


        return ResponseEntity.badRequest().body(errorResponse);
    }


    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGlobalException(Exception e) {

        // Log the real cause server-side, but never expose internal details to the client.
        log.error("Unhandled exception", e);

        ErrorResponse errorResponse = ErrorResponse.of("Internal server error", HttpStatus.INTERNAL_SERVER_ERROR.value(), HttpStatus.INTERNAL_SERVER_ERROR.name(), Instant.now());


        return ResponseEntity.internalServerError().body(errorResponse);
    }
}
