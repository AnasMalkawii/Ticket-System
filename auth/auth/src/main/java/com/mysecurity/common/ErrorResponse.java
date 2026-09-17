package com.mysecurity.common;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

@Getter
@Builder
public class ErrorResponse {
    private final String message;
    private final int httpStatus;
    private final String errorCode;
    private final Instant timestamp;


    public static ErrorResponse of(String message, int httpStatus, String errorCode, Instant timestamp) {
        return ErrorResponse.builder()
                .message(message)
                .httpStatus(httpStatus)
                .errorCode(errorCode)
                .timestamp(timestamp)
                .build();
    }
}
