package com.ticketsystem.security;

import java.util.Collection;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** Maps the single public {@code role} claim to Spring Security's {@code ROLE_*} authority. */
public final class JwtRoleAuthoritiesConverter
        implements Converter<Jwt, Collection<GrantedAuthority>> {

    public static final String ROLE_CLAIM = "role";

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        return Role.fromClaim(jwt.getClaim(ROLE_CLAIM))
                .<Collection<GrantedAuthority>>map(
                        role -> List.of(new SimpleGrantedAuthority(role.authority())))
                .orElseGet(List::of);
    }
}
