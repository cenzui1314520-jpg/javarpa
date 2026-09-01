package com.rpa.server.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtUtilTest {

    @Test
    void issueAndVerifyRoundTrip() {
        JwtUtil util = new JwtUtil("unit-test-secret-0123456789abcdef0123456789", 1);
        String token = util.issue(42L, "admin");
        assertEquals(42L, util.verify(token));
    }

    @Test
    void invalidTokenRejected() {
        JwtUtil util = new JwtUtil("unit-test-secret-0123456789abcdef0123456789", 1);
        assertThrows(ApiException.class, () -> util.verify("garbage.token.value"));
    }

    @Test
    void expiredTokenRejected() {
        JwtUtil util = new JwtUtil("unit-test-secret-0123456789abcdef0123456789", 0);
        String token = util.issue(1L, "admin");
        assertThrows(ApiException.class, () -> util.verify(token));
    }
}
