package com.ticketsystem.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/** Builds JWT authentication using {@code sub} as the principal and {@code role} as authority. */
public final class JwtRoleAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtAuthenticationConverter delegate;

    public JwtRoleAuthenticationConverter() {
        delegate = new JwtAuthenticationConverter();
        delegate.setPrincipalClaimName(JwtClaimNames.SUB);
        delegate.setJwtGrantedAuthoritiesConverter(new JwtRoleAuthoritiesConverter());
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        return delegate.convert(jwt);
    }
}
