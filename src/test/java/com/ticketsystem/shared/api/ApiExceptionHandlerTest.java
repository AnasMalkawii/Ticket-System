package com.ticketsystem.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.reservation.domain.IdempotencyInProgressException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    @DisplayName("an in-flight idempotent request returns 409 with Retry-After")
    void idempotencyInProgressAdvertisesBackoff() {
        var response = handler.handleIdempotencyInProgress(
                new IdempotencyInProgressException(2));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties())
                .containsEntry("code", "IDEMPOTENCY_IN_PROGRESS");
    }
}
