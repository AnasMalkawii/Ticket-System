package com.ticketsystem.shared.api;

import com.ticketsystem.shared.error.ErrorCode;
import java.net.URI;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** Creates the one RFC 9457 representation used by MVC, servlet, and security failures. */
public final class ApiProblemFactory {

    private static final String PROBLEM_BASE = "https://ticketsystem.dev/problems/";

    private ApiProblemFactory() {
    }

    public static ProblemDetail create(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.valueOf(code.httpStatus()), detail);
        problem.setType(URI.create(PROBLEM_BASE + slug(code)));
        problem.setTitle(title(code));
        problem.setProperty("code", code.name());
        problem.setProperty("traceId", RequestCorrelation.currentId()
                .orElseGet(() -> UUID.randomUUID().toString()));
        return problem;
    }

    private static String slug(ErrorCode code) {
        return code.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String title(ErrorCode code) {
        String words = code.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
