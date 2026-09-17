package com.ticketsystem.auth.config;

import com.ticketsystem.auth.enums.Role;
import com.ticketsystem.auth.filter.JwtAuthenticationFilter;
import com.ticketsystem.auth.security.RestAuthenticationEntryPoint;
import com.ticketsystem.auth.security.RestAccessDeniedHandler;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.ticketsystem.shared.api.ApiProblemWriter;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** Fail-closed bearer security backed by revocable database sessions. */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_HEALTH_ENDPOINTS = {
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/prometheus",
            "/livez",
            "/readyz"
    };

    private static final String[] PUBLIC_AUTH_ENDPOINTS = {
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout"
    };

    private static final String[] PUBLIC_EVENT_ENDPOINTS = {
            "/api/v1/events",
            "/api/v1/events/*",
            "/api/v1/events/*/availability"
    };

    private static final String[] USER_RESERVATION_POST_ENDPOINTS = {
            "/api/v1/events/*/reservations",
            "/api/v1/reservations/*/confirm"
    };

    @Bean
    public Clock securityClock() {
        return Clock.systemUTC();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public RestAuthenticationEntryPoint problemAuthenticationEntryPoint(
            ApiProblemWriter problemWriter) {
        return new RestAuthenticationEntryPoint(problemWriter);
    }

    @Bean
    public RestAccessDeniedHandler problemAccessDeniedHandler(ApiProblemWriter problemWriter) {
        return new RestAccessDeniedHandler(problemWriter);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(AuthProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                HttpHeaders.AUTHORIZATION,
                HttpHeaders.CONTENT_TYPE,
                "Idempotency-Key",
                "X-CSRF-TOKEN",
                "X-Request-Id"));
        configuration.setExposedHeaders(List.of("X-Request-Id", "Idempotency-Replayed"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler)
            throws Exception {
        http
                // Access tokens use Authorization headers. Cookie-authenticated refresh/logout
                // use an explicit double-submit CSRF value verified by AuthService.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth

                        // Public monitoring
                        .requestMatchers(HttpMethod.GET, PUBLIC_HEALTH_ENDPOINTS)
                        .permitAll()

                        // Admin monitoring
                        .requestMatchers(
                                HttpMethod.GET,
                                "/actuator/metrics",
                                "/actuator/metrics/**"
                        )
                        .hasRole(Role.ADMIN.name())

                        // Authentication
                        .requestMatchers(HttpMethod.POST, PUBLIC_AUTH_ENDPOINTS)
                        .permitAll()

                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me")
                        .authenticated()

                        // Public event catalog
                        .requestMatchers(HttpMethod.GET, PUBLIC_EVENT_ENDPOINTS)
                        .permitAll()

                        // User reservations
                        .requestMatchers(HttpMethod.POST, USER_RESERVATION_POST_ENDPOINTS)
                        .hasRole(Role.USER.name())

                        .requestMatchers(HttpMethod.GET, "/api/v1/reservations/*")
                        .hasAnyRole(
                                Role.USER.name(),
                                Role.ADMIN.name()
                        )

                        .requestMatchers(HttpMethod.DELETE, "/api/v1/reservations/*")
                        .hasRole(Role.USER.name())

                        // Admin operations
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/events")
                        .hasRole(Role.ADMIN.name())

                        .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/events/*")
                        .hasRole(Role.ADMIN.name())

                        // Secure by default
                        .anyRequest()
                        .denyAll()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .anonymous(Customizer.withDefaults());
        return http.build();
    }

    /** A filter bean must only run inside Spring Security, never as a second servlet filter. */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
