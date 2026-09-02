package com.rpa.server.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtUtilTest {

    private static JwtUtil util() {
        return new JwtUtil("unit-test-secret-0123456789abcdef0123456789", 1, "dev");
    }

    @Test
    void issueAndVerifyRoundTrip() {
        JwtUtil util = util();
        String token = util.issue(42L, "admin");
        assertEquals(42L, util.verify(token));
    }

    @Test
    void verifyWithIssuedAtReturnsIat() {
        JwtUtil util = util();
        long before = System.currentTimeMillis();
        String token = util.issue(7L, "admin");
        JwtUtil.AdminToken t = util.verifyWithIssuedAt(token);
        assertEquals(7L, t.adminId());
        org.junit.jupiter.api.Assertions.assertTrue(t.issuedAtMillis() >= before - 1000);
    }

    @Test
    void invalidTokenRejected() {
        assertThrows(ApiException.class, () -> util().verify("garbage.token.value"));
    }

    @Test
    void expiredTokenRejected() {
        JwtUtil util = new JwtUtil("unit-test-secret-0123456789abcdef0123456789", 0, "dev");
        String token = util.issue(1L, "admin");
        assertThrows(ApiException.class, () -> util.verify(token));
    }

    @Test
    void defaultSecretRejectedOutsideDev() {
        assertThrows(IllegalStateException.class, () ->
                new JwtUtil("change-me-in-production-0123456789abcdef", 1, "prod"));
    }

    @Test
    void defaultSecretRejectedWhenProfileUnset() {
        // 裸 java -jar 部署（未声明 profile）不得放行默认密钥
        assertThrows(IllegalStateException.class, () ->
                new JwtUtil("change-me-in-production-0123456789abcdef", 1, ""));
        assertThrows(IllegalStateException.class, () ->
                new JwtUtil("change-me-in-production-0123456789abcdef", 1, null));
    }

    @Test
    void defaultSecretAllowedInDev() {
        new JwtUtil("change-me-in-production-0123456789abcdef", 1, "dev");
    }

    @Test
    void defaultSecretAllowedInCompositeDevProfile() {
        new JwtUtil("change-me-in-production-0123456789abcdef", 1, "dev,extra");
        new JwtUtil("change-me-in-production-0123456789abcdef", 1, " local ");
    }
}
