package com.mysecurity.auth.util;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class TokenHashTest {

    @Test
    void hashIsDeterministic() {
        assertThat(TokenHash.hash("a.b.c")).isEqualTo(TokenHash.hash("a.b.c"));
    }

    @Test
    void differentInputsProduceDifferentHashes() {
        assertThat(TokenHash.hash("token-one")).isNotEqualTo(TokenHash.hash("token-two"));
    }

    @Test
    void neverReturnsTheRawToken() {
        String raw = "super-secret-token";
        assertThat(TokenHash.hash(raw)).isNotEqualTo(raw);
    }

    @Test
    void producesBase64EncodedSha256_32Bytes() {
        String hash = TokenHash.hash("whatever");
        byte[] decoded = Base64.getDecoder().decode(hash);
        assertThat(decoded).hasSize(32); // SHA-256 = 256 bits
    }
}
