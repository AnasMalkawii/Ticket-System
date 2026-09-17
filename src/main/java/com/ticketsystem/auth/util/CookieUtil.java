package com.ticketsystem.auth.util;

import com.ticketsystem.auth.config.AuthProperties;

import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public final class CookieUtil {

    private final AuthProperties properties;

    public CookieUtil(AuthProperties properties) {
        this.properties = properties;
    }

    public void addSessionCookies(HttpHeaders headers, String refreshToken, String csrfToken) {
        AuthProperties.Cookie cookie = properties.cookie();
        headers.add(HttpHeaders.SET_COOKIE, cookie(cookie.refreshName(), refreshToken, true,
                properties.refreshTokenTtl()).toString());
        headers.add(HttpHeaders.SET_COOKIE, cookie(cookie.csrfName(), csrfToken, false,
                properties.refreshTokenTtl()).toString());
    }

    public void clearSessionCookies(HttpHeaders headers) {
        AuthProperties.Cookie cookie = properties.cookie();
        headers.add(HttpHeaders.SET_COOKIE,
                cookie(cookie.refreshName(), "", true, Duration.ZERO).toString());
        headers.add(HttpHeaders.SET_COOKIE,
                cookie(cookie.csrfName(), "", false, Duration.ZERO).toString());
    }

    public String refreshCookieName() {
        return properties.cookie().refreshName();
    }

    public String csrfCookieName() {
        return properties.cookie().csrfName();
    }

    private ResponseCookie cookie(String name, String value, boolean httpOnly, Duration maxAge) {
        AuthProperties.Cookie settings = properties.cookie();
        return ResponseCookie.from(name, value)
                .httpOnly(httpOnly)
                .secure(settings.secure())
                .sameSite(settings.sameSite())
                .path(settings.path())
                .maxAge(maxAge)
                .build();
    }
}
