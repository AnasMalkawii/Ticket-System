package com.ticketsystem.security;

import com.ticketsystem.shared.api.ApiProblemWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/** Stateless JWT authentication, configured accounts, and fail-closed endpoint policy. */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    public SecretKey jwtSigningKey(SecurityProperties properties) {
        return new SecretKeySpec(
                properties.signingKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return NimbusJwtEncoder.withSecretKey(jwtSigningKey)
                .algorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSigningKey, SecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        OAuth2TokenValidator<Jwt> issuerAndTime =
                JwtValidators.createDefaultWithIssuer(properties.issuer());
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<Object>(
                JwtClaimNames.AUD,
                claim -> hasAudience(claim, properties.audience()));
        OAuth2TokenValidator<Jwt> subject = new JwtClaimValidator<Object>(
                JwtClaimNames.SUB,
                claim -> claim instanceof String value && isUuid(value));
        OAuth2TokenValidator<Jwt> role = new JwtClaimValidator<Object>(
                JwtRoleAuthoritiesConverter.ROLE_CLAIM,
                claim -> Role.fromClaim(claim).isPresent());

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                issuerAndTime, audience, subject, role));
        return decoder;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public UserDetailsService configuredUsers(
            SecurityProperties properties, Environment environment) {
        boolean testProfile = environment.acceptsProfiles(Profiles.of("test"));
        boolean insecurePassword = properties.accounts().stream()
                .anyMatch(account -> account.passwordHash().startsWith("{noop}"));
        if (!testProfile && insecurePassword) {
            throw new IllegalArgumentException(
                    "{noop} passwords are permitted only in the test profile");
        }
        UserDetails[] users = properties.accounts().stream()
                .map(account -> User.withUsername(account.username())
                        .password(account.passwordHash())
                        .authorities(account.role().authority())
                        .build())
                .toArray(UserDetails[]::new);
        return new InMemoryUserDetailsManager(users);
    }

    @Bean
    public AuthenticationManager authenticationManager(
            UserDetailsService configuredUsers, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(configuredUsers);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    public JwtRoleAuthenticationConverter jwtRoleAuthenticationConverter() {
        return new JwtRoleAuthenticationConverter();
    }

    @Bean
    public ProblemAuthenticationEntryPoint problemAuthenticationEntryPoint(
            ApiProblemWriter problemWriter) {
        return new ProblemAuthenticationEntryPoint(problemWriter);
    }

    @Bean
    public ProblemAccessDeniedHandler problemAccessDeniedHandler(ApiProblemWriter problemWriter) {
        return new ProblemAccessDeniedHandler(problemWriter);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtRoleAuthenticationConverter jwtAuthenticationConverter,
            ProblemAuthenticationEntryPoint authenticationEntryPoint,
            ProblemAccessDeniedHandler accessDeniedHandler)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET,
                                "/actuator/health", "/actuator/health/*",
                                "/actuator/prometheus", "/livez", "/readyz").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/actuator/metrics", "/actuator/metrics/*")
                                .hasRole(Role.ADMIN.name())
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/events",
                                "/api/v1/events/*",
                                "/api/v1/events/*/availability").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/events/*/reservations").hasRole(Role.USER.name())
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/reservations/*")
                                .hasAnyRole(Role.USER.name(), Role.ADMIN.name())
                        .requestMatchers(HttpMethod.DELETE,
                                "/api/v1/reservations/*").hasRole(Role.USER.name())
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/reservations/*/confirm").hasRole(Role.USER.name())
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/admin/events").hasRole(Role.ADMIN.name())
                        .requestMatchers(HttpMethod.PATCH,
                                "/api/v1/admin/events/*").hasRole(Role.ADMIN.name())
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .anonymous(Customizer.withDefaults());

        return http.build();
    }

    private static boolean isUuid(String subject) {
        if (subject == null) {
            return false;
        }
        try {
            UUID.fromString(subject);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean hasAudience(Object claim, String requiredAudience) {
        if (claim instanceof String audience) {
            return requiredAudience.equals(audience);
        }
        if (claim instanceof Collection<?> audiences) {
            return audiences.stream().anyMatch(requiredAudience::equals);
        }
        return false;
    }
}
