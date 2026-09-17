package com.ticketsystem.auth.util;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;

public final class TokenExtractor {
    private TokenExtractor() {}

    public static String extractAccessToken(HttpServletRequest request) {
        var headers = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
        if (headers.isEmpty()) return null;
        if (headers.size() != 1) throw new BadCredentialsException("Ambiguous Authorization header");
        String header = headers.getFirst();
        if (!header.regionMatches(true, 0, "Bearer ", 0, 7) || header.length() <= 7) {
            throw new BadCredentialsException("Invalid Authorization header");
        }
        String token = header.substring(7);
        if (token.length() > 4096 || token.chars().anyMatch(Character::isWhitespace)) {
            throw new BadCredentialsException("Invalid bearer token");
        }
        return token;
    }
}
