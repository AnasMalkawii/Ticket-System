package com.mysecurity.auth.util;

import com.mysecurity.auth.config.CookieProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

public final class CookieUtil {


    private final static String REFRESH_TOKEN_NAME = "refresh_token";


    private CookieUtil() {}

    public static void addRefreshTokenToCookie(final HttpServletResponse response, final String refreshToken, final CookieProperties cookieProperties) {

        ResponseCookie cookie = ResponseCookie.from(REFRESH_TOKEN_NAME, refreshToken)
                .httpOnly(true)
                .secure(cookieProperties.secure())
                .sameSite(cookieProperties.sameSite())
                .path("/api/auth")
                .maxAge(Duration.ofDays(7))
                .build();

        response.addHeader("Set-Cookie", cookie.toString());

    }

    public static void removeRefreshTokenFromCookie(final HttpServletResponse response, final CookieProperties cookieProperties) {

        ResponseCookie cookie = ResponseCookie.from(REFRESH_TOKEN_NAME, "")
                .httpOnly(true)
                .secure(cookieProperties.secure())
                .sameSite(cookieProperties.sameSite())
                .path("/api/auth")
                .maxAge(Duration.ofDays(0))
                .build();

        response.addHeader("Set-Cookie", cookie.toString());

    }

}
