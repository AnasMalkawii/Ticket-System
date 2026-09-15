package com.ticketsystem.shared.api;

import com.ticketsystem.shared.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes one safe correlation id before security, MVC, and application logging run.
 *
 * <p>The wrapped request exposes a generated id as an ordinary {@code X-Request-Id} header,
 * so existing controllers propagate it without needing a second correlation-id API.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    private final ApiProperties properties;
    private final ApiProblemWriter problemWriter;

    public RequestCorrelationFilter(ApiProperties properties, ApiProblemWriter problemWriter) {
        this.properties = properties;
        this.problemWriter = problemWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String supplied = request.getHeader(RequestCorrelation.HEADER_NAME);
        String invalidReason = invalidReason(supplied);
        String requestId = supplied == null || supplied.isBlank() || invalidReason != null
                ? UUID.randomUUID().toString()
                : supplied;
        String previousRequestId = MDC.get(RequestCorrelation.MDC_KEY);

        request.setAttribute(RequestCorrelation.REQUEST_ATTRIBUTE, requestId);
        response.setHeader(RequestCorrelation.HEADER_NAME, requestId);
        MDC.put(RequestCorrelation.MDC_KEY, requestId);
        try {
            if (invalidReason != null) {
                problemWriter.write(response, ErrorCode.VALIDATION_ERROR, invalidReason);
                return;
            }
            filterChain.doFilter(new RequestIdHeaderRequest(request, requestId), response);
        } finally {
            if (previousRequestId == null) {
                MDC.remove(RequestCorrelation.MDC_KEY);
            } else {
                MDC.put(RequestCorrelation.MDC_KEY, previousRequestId);
            }
        }
    }

    private String invalidReason(String supplied) {
        if (supplied == null || supplied.isBlank()) {
            return null;
        }
        if (supplied.length() > properties.getMaxRequestIdLength()) {
            return "X-Request-Id must be at most %d characters."
                    .formatted(properties.getMaxRequestIdLength());
        }
        if (supplied.chars().anyMatch(character -> Character.isISOControl(character))) {
            return "X-Request-Id must not contain control characters.";
        }
        return null;
    }

    private static final class RequestIdHeaderRequest extends HttpServletRequestWrapper {

        private final String requestId;

        private RequestIdHeaderRequest(HttpServletRequest request, String requestId) {
            super(request);
            this.requestId = requestId;
        }

        @Override
        public String getHeader(String name) {
            return RequestCorrelation.HEADER_NAME.equalsIgnoreCase(name)
                    ? requestId
                    : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return RequestCorrelation.HEADER_NAME.equalsIgnoreCase(name)
                    ? Collections.enumeration(List.of(requestId))
                    : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = new ArrayList<>(Collections.list(super.getHeaderNames()));
            boolean alreadyPresent = names.stream()
                    .anyMatch(RequestCorrelation.HEADER_NAME::equalsIgnoreCase);
            if (!alreadyPresent) {
                names.add(RequestCorrelation.HEADER_NAME);
            }
            return Collections.enumeration(names);
        }
    }
}
