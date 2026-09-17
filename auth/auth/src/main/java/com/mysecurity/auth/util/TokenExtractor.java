package com.mysecurity.auth.util;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class TokenExtractor {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String REFRESH_COOKIE_NAME = "refresh_token";

    private TokenExtractor() {}


    public static String extractRefreshToken(HttpServletRequest request) {

        if (request.getCookies() == null) {
            return null;
        }
        return Arrays.stream(request.getCookies())
                .filter(cookie ->REFRESH_COOKIE_NAME.equals(cookie.getName()))
                .map(cookie -> URLDecoder.decode(cookie.getValue(), StandardCharsets.UTF_8))
                .findFirst()
                .orElse(null);
    }

    public static String extractAccessToken(HttpServletRequest request) {
        final String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return authHeader.substring(BEARER_PREFIX.length());
    }


    public static boolean hasBearerHeader(HttpServletRequest request) {
        final String authHeader = request.getHeader("Authorization");
        return authHeader != null && authHeader.startsWith(BEARER_PREFIX);
    }

}
