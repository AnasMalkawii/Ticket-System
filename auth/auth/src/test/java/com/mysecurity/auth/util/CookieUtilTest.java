package com.mysecurity.auth.util;

import com.mysecurity.auth.config.CookieProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CookieUtilTest {

    @Test
    void addsHardenedRefreshCookieInProdSettings() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        CookieUtil.addRefreshTokenToCookie(response, "jwt-value", new CookieProperties(true, "Strict"));

        String setCookie = response.getHeader("Set-Cookie");
        assertThat(setCookie).isNotNull();
        assertThat(setCookie).contains("refresh_token=jwt-value");
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("Secure");
        assertThat(setCookie).contains("SameSite=Strict");
        assertThat(setCookie).contains("Path=/api/auth");
        assertThat(setCookie).contains("Max-Age=604800"); // 7 days
    }

    @Test
    void omitsSecureFlagInDevSettings() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        CookieUtil.addRefreshTokenToCookie(response, "jwt-value", new CookieProperties(false, "Lax"));

        String setCookie = response.getHeader("Set-Cookie");
        assertThat(setCookie).contains("SameSite=Lax");
        assertThat(setCookie).doesNotContain("Secure");
    }

    @Test
    void removalExpiresTheCookieImmediately() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        CookieUtil.removeRefreshTokenFromCookie(response, new CookieProperties(true, "Strict"));

        String setCookie = response.getHeader("Set-Cookie");
        assertThat(setCookie).contains("refresh_token=");
        assertThat(setCookie).contains("Max-Age=0");
        assertThat(setCookie).contains("Path=/api/auth");
    }
}
