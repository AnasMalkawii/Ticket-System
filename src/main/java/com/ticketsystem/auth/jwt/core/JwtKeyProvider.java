package com.ticketsystem.auth.jwt.core;

import com.ticketsystem.auth.config.AuthProperties;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Adapted from auth: two independently configured signing keys, validated at startup. */
@Component
public final class JwtKeyProvider {
    private final SecretKey accessKey;
    private final SecretKey refreshKey;

    public JwtKeyProvider(AuthProperties properties) {
        accessKey = new SecretKeySpec(Base64.getDecoder().decode(properties.accessSecret()), "HmacSHA256");
        refreshKey = new SecretKeySpec(Base64.getDecoder().decode(properties.refreshSecret()), "HmacSHA256");
    }

    public SecretKey getAccessKey() { return accessKey; }
    public SecretKey getRefreshKey() { return refreshKey; }
}
