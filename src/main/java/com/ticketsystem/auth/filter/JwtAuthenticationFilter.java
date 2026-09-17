package com.ticketsystem.auth.filter;

import com.ticketsystem.auth.security.AuthenticatedUser;
import com.ticketsystem.auth.security.RestAuthenticationEntryPoint;
import com.ticketsystem.auth.service.refreshtoken.RefreshTokenValidator;
import com.ticketsystem.auth.util.TokenExtractor;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** The auth module's explicit bearer filter, with Ticket System session and role checks. */
@Component
public final class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final RefreshTokenValidator validator;
    private final RestAuthenticationEntryPoint entryPoint;

    public JwtAuthenticationFilter(RefreshTokenValidator validator, RestAuthenticationEntryPoint entryPoint) {
        this.validator = validator;
        this.entryPoint = entryPoint;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // These operations authenticate their own password or refresh+CSRF credentials.
        // A client may still attach an expired access token while refreshing or logging out.
        if (!"POST".equals(request.getMethod())) return false;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return switch (path) {
            case "/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/refresh", "/api/v1/auth/logout" -> true;
            default -> false;
        };
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String token = TokenExtractor.extractAccessToken(request);
            if (token != null) {
                AuthenticatedUser user = validator.authenticateAccessToken(token);
                var authentication = UsernamePasswordAuthenticationToken.authenticated(user, null,
                        List.of(new SimpleGrantedAuthority(user.role().authority())));
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            }
        } catch (JwtException | IllegalArgumentException | AuthenticationException ex) {
            SecurityContextHolder.clearContext();
            entryPoint.commence(request, response, new BadCredentialsException("Invalid bearer token"));
            return;
        }
        chain.doFilter(request, response);
    }
}
