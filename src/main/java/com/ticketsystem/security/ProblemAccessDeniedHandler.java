package com.ticketsystem.security;

import com.ticketsystem.shared.api.ApiProblemWriter;
import com.ticketsystem.shared.error.ErrorCode;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/** Writes the same RFC 9457 contract for authorization failures raised before MVC. */
public final class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private final ApiProblemWriter problemWriter;

    public ProblemAccessDeniedHandler(ApiProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception)
            throws IOException, ServletException {
        problemWriter.write(response, ErrorCode.FORBIDDEN,
                "The authenticated user is not permitted to perform this operation.");
    }
}
