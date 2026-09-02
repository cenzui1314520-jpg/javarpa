package com.rpa.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rpa.server.common.ApiException;
import com.rpa.server.common.JwtUtil;
import com.rpa.server.entity.AdminUser;
import com.rpa.server.mapper.AdminUserMapper;
import com.rpa.server.mapper.ApiTokenMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class AuthServiceTest {
    private AdminUserMapper adminUserMapper;
    private AuthService authService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private AdminUser user() {
        AdminUser user = new AdminUser();
        user.id = 1L;
        user.username = "admin";
        user.passwordHash = encoder.encode("right-pass");
        user.status = 1;
        return user;
    }

    @BeforeEach
    void setup() {
        adminUserMapper = Mockito.mock(AdminUserMapper.class);
        JwtUtil jwtUtil = new JwtUtil("unit-test-secret-0123456789abcdef0123456789", 1, "dev");
        authService = new AuthService(adminUserMapper, Mockito.mock(ApiTokenMapper.class), jwtUtil);
    }

    @Test
    void loginLockedAfterFiveFailures() {
        when(adminUserMapper.selectOne(any(QueryWrapper.class))).thenReturn(user());
        for (int i = 0; i < 5; i++) {
            ApiException ex = assertThrows(ApiException.class, () -> authService.login("admin", "wrong"));
            assertEquals(400, ex.getCode());
        }
        // 锁定期间即使密码正确也拒绝
        ApiException locked = assertThrows(ApiException.class, () -> authService.login("admin", "right-pass"));
        assertEquals(429, locked.getCode());
    }

    @Test
    void loginSuccessResetsFailureCount() {
        when(adminUserMapper.selectOne(any(QueryWrapper.class))).thenReturn(user());
        for (int i = 0; i < 4; i++) {
            assertThrows(ApiException.class, () -> authService.login("admin", "wrong"));
        }
        authService.login("admin", "right-pass");
        // 成功后计数清零，单次失败不触发锁定
        ApiException ex = assertThrows(ApiException.class, () -> authService.login("admin", "wrong"));
        assertEquals(400, ex.getCode());
    }

    @Test
    void staleTokenRejectedAfterPasswordChange() {
        when(adminUserMapper.selectById(1L)).thenReturn(user());
        long staleIat = System.currentTimeMillis() - 5000;
        authService.changePassword(1L, "right-pass", "new-pass-123");
        ApiException ex = assertThrows(ApiException.class, () ->
                authService.assertTokenFresh(1L, staleIat));
        assertEquals(401, ex.getCode());
    }

    @Test
    void freshTokenAcceptedAfterPasswordChange() {
        when(adminUserMapper.selectById(1L)).thenReturn(user());
        authService.changePassword(1L, "right-pass", "new-pass-123");
        long freshIat = System.currentTimeMillis();
        authService.assertTokenFresh(1L, freshIat);
    }
}
