package com.ticketsystem.auth.controller;

import com.ticketsystem.auth.util.TokenIssuer.IssuedTokens;
import com.ticketsystem.auth.dto.login.LoginRequest;
import com.ticketsystem.auth.dto.login.LoginResponse;
import com.ticketsystem.auth.dto.register.RegisterRequest;
import com.ticketsystem.auth.dto.register.RegisterResponse;
import com.ticketsystem.auth.security.AuthenticatedUser;
import com.ticketsystem.auth.service.auth.AuthService;
import com.ticketsystem.auth.util.CookieUtil;
import com.ticketsystem.user.dto.UserResponse;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String CSRF_HEADER = "X-CSRF-TOKEN";

    private final AuthService authService;
    private final CookieUtil cookies;

    public AuthController(AuthService authService, CookieUtil cookies) {
        this.authService = authService;
        this.cookies = cookies;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(
            @Valid @RequestBody RegisterRequest request, HttpServletRequest servletRequest) {
        RegisterResponse user = authService.register(
                request.username(), request.password(), clientIp(servletRequest));
        return ResponseEntity.created(URI.create("/api/v1/auth/me"))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(user);
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        IssuedTokens result = authService.login(
                request.username(), request.password(), clientIp(servletRequest),
                servletRequest.getHeader(HttpHeaders.USER_AGENT));
        return tokenResponse(result);
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(
            @RequestHeader(name = CSRF_HEADER, required = false) String csrfHeader,
            HttpServletRequest request) {
        IssuedTokens result = authService.refresh(
                cookieValue(request, cookies.refreshCookieName()),
                cookieValue(request, cookies.csrfCookieName()),
                csrfHeader,
                clientIp(request),
                request.getHeader(HttpHeaders.USER_AGENT));
        return tokenResponse(result);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(name = CSRF_HEADER, required = false) String csrfHeader,
            HttpServletRequest request) {
        HttpHeaders headers = noStoreHeaders();
        cookies.clearSessionCookies(headers);
        authService.logout(
                cookieValue(request, cookies.refreshCookieName()),
                cookieValue(request, cookies.csrfCookieName()),
                csrfHeader);
        return new ResponseEntity<>(headers, org.springframework.http.HttpStatus.NO_CONTENT);
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(Authentication authentication) {
        AuthenticatedUser principal = AuthenticatedUser.from(authentication);
        return ResponseEntity.ok()
                .headers(noStoreHeaders())
                .body(authService.currentUser(principal.userId()));
    }

    private ResponseEntity<LoginResponse> tokenResponse(IssuedTokens result) {
        HttpHeaders headers = noStoreHeaders();
        cookies.addSessionCookies(headers, result.refreshToken(), result.csrfToken());
        return ResponseEntity.ok().headers(headers).body(LoginResponse.from(result));
    }

    private static HttpHeaders noStoreHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore());
        headers.setPragma("no-cache");
        return headers;
    }

    private static String cookieValue(HttpServletRequest request, String name) {
        Cookie[] requestCookies = request.getCookies();
        if (requestCookies == null) {
            return null;
        }
        for (Cookie cookie : requestCookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
