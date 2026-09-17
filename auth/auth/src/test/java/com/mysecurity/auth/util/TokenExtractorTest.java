package com.mysecurity.auth.util;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class TokenExtractorTest {

    @Test
    void extractsBearerAccessToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer abc.def.ghi");
        assertThat(TokenExtractor.extractAccessToken(request)).isEqualTo("abc.def.ghi");
    }

    @Test
    void returnsNullWhenAuthorizationHeaderMissing() {
        assertThat(TokenExtractor.extractAccessToken(new MockHttpServletRequest())).isNull();
    }

    @Test
    void returnsNullWhenHeaderIsNotBearer() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic abc.def.ghi");
        assertThat(TokenExtractor.extractAccessToken(request)).isNull();
    }

    @Test
    void hasBearerHeaderReflectsPresence() {
        MockHttpServletRequest withBearer = new MockHttpServletRequest();
        withBearer.addHeader("Authorization", "Bearer x");
        assertThat(TokenExtractor.hasBearerHeader(withBearer)).isTrue();
        assertThat(TokenExtractor.hasBearerHeader(new MockHttpServletRequest())).isFalse();
    }

    @Test
    void extractsRefreshTokenFromCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("refresh_token", "the-refresh-token"));
        assertThat(TokenExtractor.extractRefreshToken(request)).isEqualTo("the-refresh-token");
    }

    @Test
    void returnsNullWhenNoCookiesPresent() {
        assertThat(TokenExtractor.extractRefreshToken(new MockHttpServletRequest())).isNull();
    }

    @Test
    void returnsNullWhenRefreshCookieAbsentAmongOthers() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("some_other", "value"));
        assertThat(TokenExtractor.extractRefreshToken(request)).isNull();
    }
}
