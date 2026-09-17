package com.mysecurity.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cookie attributes for the refresh-token cookie.
 * Values are profile-driven: relaxed in dev (application.yaml),
 * hardened in prod (application-prod.yaml).
 */
@ConfigurationProperties(prefix = "app.cookie")
public record CookieProperties(
        boolean secure,
        String sameSite
) {
}
