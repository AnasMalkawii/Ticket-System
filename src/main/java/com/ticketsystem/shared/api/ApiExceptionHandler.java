package com.ticketsystem.shared.api;

import com.ticketsystem.reservation.domain.IdempotencyInProgressException;
import com.ticketsystem.shared.error.DomainException;
import com.ticketsystem.shared.error.ErrorCode;
import com.ticketsystem.shared.ratelimit.RateLimitExceededException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;

/**
 * Translates every failure into the RFC 9457 {@code application/problem+json} contract
 * documented in {@code docs/api/openapi.yaml}.
 *
 * <p>The rule this class exists to enforce: <strong>a business rejection is a 4xx carrying a
 * stable {@code code}, never a 5xx.</strong> A sold-out flash sale must produce fast, cheap,
 * correct rejections rather than a 5xx storm, because only 5xx burns the availability error
 * budget in {@code docs/slo.md}. Clients and k6 scenarios branch on {@code code}; the human
 * readable {@code detail} is never parsed.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    /** Every rule violation the domain raises, mapped by its own declared error code. */
    @ExceptionHandler(DomainException.class)
    public ProblemDetail handleDomain(DomainException exception) {
        return problem(exception.errorCode(), exception.getMessage());
    }

    /** A bounded duplicate-key wait is safe to retry and advertises its backoff. */
    @ExceptionHandler(IdempotencyInProgressException.class)
    public ResponseEntity<ProblemDetail> handleIdempotencyInProgress(
            IdempotencyInProgressException exception) {
        return ResponseEntity.status(exception.errorCode().httpStatus())
                .header(HttpHeaders.RETRY_AFTER,
                        Integer.toString(exception.getRetryAfterSeconds()))
                .body(problem(exception.errorCode(), exception.getMessage()));
    }

    /** Rate limiting is a deliberate business response and always advertises its window. */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimited(
            RateLimitExceededException exception) {
        return ResponseEntity.status(exception.errorCode().httpStatus())
                .header(HttpHeaders.RETRY_AFTER,
                        Integer.toString(exception.getRetryAfterSeconds()))
                .body(problem(exception.errorCode(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return problem(ErrorCode.VALIDATION_ERROR, detail.isBlank() ? "Invalid request" : detail);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail handleMissingHeader(MissingRequestHeaderException exception) {
        if ("Idempotency-Key".equalsIgnoreCase(exception.getHeaderName())) {
            return problem(ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                    "The Idempotency-Key header is mandatory for this operation.");
        }
        return problem(ErrorCode.VALIDATION_ERROR,
                "Missing required header: " + exception.getHeaderName());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ProblemDetail handleMalformedRequest(Exception exception) {
        RequestBodyTooLargeException tooLarge = findCause(
                exception, RequestBodyTooLargeException.class);
        if (tooLarge != null) {
            return problem(ErrorCode.VALIDATION_ERROR, tooLarge.getMessage());
        }
        return problem(ErrorCode.VALIDATION_ERROR, "Malformed request: " + exception.getMessage());
    }

    /**
     * Idempotency conflicts are resolved by the reserve coordinator after its failed insert
     * transaction rolls back. Any constraint violation still reaching the API is therefore
     * an unexpected invariant or mapping defect, not a client-visible duplicate-key outcome.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleConstraintViolation(DataIntegrityViolationException exception) {
        log.error("Unexpected constraint violation", exception);
        return problem(ErrorCode.INTERNAL_ERROR, "A data constraint was violated.");
    }

    /**
     * Database acquisition, query, and lock deadlines are availability failures, not opaque
     * 500s. The transaction has rolled back, so clients may safely retry an idempotent request
     * with backoff.
     */
    @ExceptionHandler({TransientDataAccessException.class,
            DataAccessResourceFailureException.class,
            CannotCreateTransactionException.class,
            TransactionTimedOutException.class})
    public ResponseEntity<ProblemDetail> handleDependencyUnavailable(Exception exception) {
        log.warn("Required database operation failed within its configured deadline: {}",
                rootMessage(exception));
        return ResponseEntity.status(ErrorCode.DEPENDENCY_UNAVAILABLE.httpStatus())
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem(ErrorCode.DEPENDENCY_UNAVAILABLE,
                        "A required dependency is temporarily unavailable."));
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception exception) {
        ProblemDetail problem = problem(ErrorCode.INTERNAL_ERROR, "Unexpected server error.");
        // The only place a stack trace belongs. The traceId in the response is the client's
        // handle for finding this line.
        log.error("Unhandled exception [traceId={}]", problem.getProperties().get("traceId"),
                exception);
        return problem;
    }

    private ProblemDetail problem(ErrorCode code, String detail) {
        return ApiProblemFactory.create(code, detail);
    }

    private static <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            if (current == current.getCause()) {
                break;
            }
            current = current.getCause();
        }
        return null;
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }
}
