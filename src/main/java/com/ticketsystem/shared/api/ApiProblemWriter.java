package com.ticketsystem.shared.api;

import com.ticketsystem.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Writes the public problem contract from filters that run outside Spring MVC advice. */
@Component
public class ApiProblemWriter {

    private final ObjectMapper objectMapper;

    public ApiProblemWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, ErrorCode code, String detail)
            throws IOException {
        write(response, code, detail, null);
    }

    public void write(HttpServletResponse response, ErrorCode code, String detail,
                      Integer retryAfterSeconds) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(code.httpStatus());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        RequestCorrelation.currentId()
                .ifPresent(requestId -> response.setHeader(RequestCorrelation.HEADER_NAME, requestId));
        if (retryAfterSeconds != null) {
            response.setHeader(HttpHeaders.RETRY_AFTER,
                    Integer.toString(Math.max(0, retryAfterSeconds)));
        }
        objectMapper.writeValue(response.getOutputStream(), ApiProblemFactory.create(code, detail));
    }
}
