package com.ticketsystem.shared.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.slf4j.MDC;

/** Shared names and lookups for one request's correlation identifier. */
public final class RequestCorrelation {

    public static final String HEADER_NAME = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    public static final String REQUEST_ATTRIBUTE =
            RequestCorrelation.class.getName() + ".requestId";

    private RequestCorrelation() {
    }

    public static Optional<String> currentId() {
        return Optional.ofNullable(MDC.get(MDC_KEY));
    }

    public static Optional<String> from(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ATTRIBUTE);
        return value instanceof String requestId && !requestId.isBlank()
                ? Optional.of(requestId)
                : Optional.empty();
    }

    /** Installs a correlation id for one background unit of work and restores its caller. */
    public static Scope open(String requestId) {
        String previous = MDC.get(MDC_KEY);
        if (requestId == null || requestId.isBlank()) {
            MDC.remove(MDC_KEY);
        } else {
            MDC.put(MDC_KEY, requestId);
        }
        return new Scope(previous);
    }

    public static final class Scope implements AutoCloseable {

        private final String previous;
        private boolean closed;

        private Scope(String previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }
}
