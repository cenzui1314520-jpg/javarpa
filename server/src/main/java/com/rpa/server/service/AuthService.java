package com.rpa.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rpa.server.common.ApiException;
import com.rpa.server.entity.AdminUser;
import com.rpa.server.entity.ApiToken;
import com.rpa.server.mapper.AdminUserMapper;
import com.rpa.server.mapper.ApiTokenMapper;
import com.rpa.server.common.DigestUtil;
import com.rpa.server.common.JwtUtil;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AuthService {
    private final AdminUserMapper adminUserMapper;
    private final ApiTokenMapper apiTokenMapper;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final JwtUtil jwtUtil;

    public AuthService(AdminUserMapper adminUserMapper, ApiTokenMapper apiTokenMapper, JwtUtil jwtUtil) {
        this.adminUserMapper = adminUserMapper;
        this.apiTokenMapper = apiTokenMapper;
        this.jwtUtil = jwtUtil;
    }

    public Map<String, Object> login(String username, String password) {
        if (username == null || password == null) throw new ApiException("用户名或密码错误");
        AdminUser user = adminUserMapper.selectOne(
                new QueryWrapper<AdminUser>().eq("username", username).last("LIMIT 1"));
        if (user == null || !encoder.matches(password, user.passwordHash)) {
            throw new ApiException("用户名或密码错误");
        }
        if (user.status == null || user.status != 1) throw new ApiException("账号已禁用");
        Map<String, Object> result = new HashMap<>();
        result.put("token", jwtUtil.issue(user.id, user.username));
        Map<String, Object> admin = new HashMap<>();
        admin.put("id", user.id);
        admin.put("username", user.username);
        admin.put("nickname", user.nickname);
        admin.put("role", user.role);
        result.put("admin", admin);
        return result;
    }

    public AdminUser byId(long id) {
        AdminUser user = adminUserMapper.selectById(id);
        if (user == null) throw new ApiException(401, "用户不存在");
        return user;
    }

    public void changePassword(long id, String oldPassword, String newPassword) {
        AdminUser user = byId(id);
        if (!encoder.matches(oldPassword, user.passwordHash)) {
            throw new ApiException("原密码错误");
        }
        if (newPassword == null || newPassword.length() < 6) {
            throw new ApiException("新密码至少 6 位");
        }
        AdminUser upd = new AdminUser();
        upd.id = id;
        upd.passwordHash = encoder.encode(newPassword);
        adminUserMapper.updateById(upd);
    }

    // ---------- API tokens for external systems ----------

    public Map<String, Object> createToken(String name) {
        if (name == null || name.isBlank()) throw new ApiException("名称不能为空");
        String token = "rpat_" + DigestUtil.randomToken(40);
        ApiToken t = new ApiToken();
        t.name = name;
        t.prefix = token.substring(0, 12);
        t.tokenHash = DigestUtil.sha256Hex(token);
        t.status = 1;
        apiTokenMapper.insert(t);
        Map<String, Object> result = new HashMap<>();
        result.put("id", t.id);
        result.put("name", name);
        result.put("token", token);
        result.put("prefix", t.prefix);
        return result;
    }

    public List<ApiToken> listTokens() {
        List<ApiToken> tokens = apiTokenMapper.selectList(
                new QueryWrapper<ApiToken>().orderByDesc("id"));
        tokens.forEach(t -> t.tokenHash = null);
        return tokens;
    }

    public void setTokenStatus(long id, int status) {
        ApiToken t = new ApiToken();
        t.id = id;
        t.status = status;
        apiTokenMapper.updateById(t);
    }
}
