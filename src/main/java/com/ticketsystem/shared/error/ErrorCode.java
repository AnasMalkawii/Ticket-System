package com.ticketsystem.shared.error;

/**
 * Stable, machine-readable outcome codes. These are part of the public API contract:
 * every value here appears in the {@code ErrorCode} enum of {@code docs/api/openapi.yaml},
 * and clients and k6 scenarios branch on them rather than on human-readable text.
 *
 * <p>Codes are grouped by whether they consume the availability error budget defined in
 * {@code docs/slo.md}. Business rejections mean the system worked correctly and refused;
 * {@link #INTERNAL_ERROR}, {@link #DEPENDENCY_UNAVAILABLE}, and
 * {@link #DEPENDENCY_TIMEOUT} indicate a server fault.
 */
public enum ErrorCode {

    // --- Client-side faults (400/401/403/404) ------------------------------------
    VALIDATION_ERROR(400),
    INVALID_QUANTITY(400),
    IDEMPOTENCY_KEY_REQUIRED(400),
    UNAUTHORIZED(401),
    FORBIDDEN(403),
    EVENT_NOT_FOUND(404),
    RESERVATION_NOT_FOUND(404),

    // --- Business rejections: the answer is "no", and that is a correct answer ----
    SALE_NOT_STARTED(409),
    SALE_ENDED(409),
    SOLD_OUT(409),
    USER_LIMIT_EXCEEDED(409),
    RESERVATION_EXPIRED(409),
    INVALID_STATE(409),
    IDEMPOTENCY_KEY_CONFLICT(409),
    IDEMPOTENCY_IN_PROGRESS(409),
    PAYMENT_DECLINED(402),
    RATE_LIMITED(429),

    // --- Server faults: these, and only these, burn the error budget --------------
    DEPENDENCY_UNAVAILABLE(503),
    DEPENDENCY_TIMEOUT(504),
    INTERNAL_ERROR(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** Whether this outcome counts against the availability SLO (A1-A4). */
    public boolean isServerFault() {
        return this == INTERNAL_ERROR
                || this == DEPENDENCY_UNAVAILABLE
                || this == DEPENDENCY_TIMEOUT;
    }
}
