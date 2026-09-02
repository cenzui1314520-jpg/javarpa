package com.rpa.server.controller;

import com.rpa.server.common.R;
import com.rpa.server.service.AuthService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody Map<String, String> body,
                                        jakarta.servlet.http.HttpServletRequest request) {
        // 防爆破按 IP+用户名双维度计数；反代场景优先取 X-Forwarded-For 首段
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank()) {
            ip = ip.split(",")[0].trim();
        } else {
            ip = request.getRemoteAddr();
        }
        return R.ok(authService.login(body.get("username"), body.get("password"), ip));
    }

    @PostMapping("/change-password")
    public R<Void> changePassword(@RequestAttribute("adminId") Long adminId,
                                  @RequestBody Map<String, String> body) {
        authService.changePassword(adminId, body.get("oldPassword"), body.get("newPassword"));
        return R.ok();
    }

    @RequestMapping("/me")
    public R<Object> me(@RequestAttribute("adminId") Long adminId) {
        var user = authService.byId(adminId);
        return R.ok(Map.of(
                "id", user.id,
                "username", user.username,
                "nickname", user.nickname,
                "role", user.role));
    }
}
