package com.rpa.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rpa.server.common.ApiException;
import com.rpa.server.entity.AdminTokenState;
import com.rpa.server.entity.AdminUser;
import com.rpa.server.entity.ApiToken;
import com.rpa.server.mapper.AdminTokenStateMapper;
import com.rpa.server.mapper.AdminUserMapper;
import com.rpa.server.mapper.ApiTokenMapper;
import com.rpa.server.common.DigestUtil;
import com.rpa.server.common.JwtUtil;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {
    private static final int MAX_LOGIN_FAILURES = 5;
    private static final long LOGIN_LOCK_MILLIS = 15 * 60_000L;
    // 用户不存在时用该哈希做一次哑比对，抹平响应时间差防用户名枚举
    private static final String DUMMY_HASH = "$2a$10$Y7X9tGRmF0OwO5qZ2aOBXeQv0Wk3zM5Qy1P6xJ8dR2kN4vB7cL9dW";

    private final AdminUserMapper adminUserMapper;
    private final ApiTokenMapper apiTokenMapper;
    private final AdminTokenStateMapper tokenStateMapper;
    // 可空：单测环境无 Redis 时退化为内存计数
    private final StringRedisTemplate redisTemplate;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final JwtUtil jwtUtil;
    // Redis 不可用时的内存兜底：ip:user -> [失败次数, 锁定截止时间]
    private final Map<String, LoginState> loginFailures = new ConcurrentHashMap<>();
    // adminId -> 最近一次改密时间（读穿缓存，真源在 admin_token_state 表）
    private final Map<Long, Long> passwordChangedAt = new ConcurrentHashMap<>();

    private record LoginState(int count, long lockedUntil) {}

    public AuthService(AdminUserMapper adminUserMapper, ApiTokenMapper apiTokenMapper,
                       JwtUtil jwtUtil) {
        this(adminUserMapper, apiTokenMapper, null, null, jwtUtil);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AuthService(AdminUserMapper adminUserMapper, ApiTokenMapper apiTokenMapper,
                       AdminTokenStateMapper tokenStateMapper, StringRedisTemplate redisTemplate,
                       JwtUtil jwtUtil) {
        this.adminUserMapper = adminUserMapper;
        this.apiTokenMapper = apiTokenMapper;
        this.tokenStateMapper = tokenStateMapper;
        this.redisTemplate = redisTemplate;
        this.jwtUtil = jwtUtil;
    }

    public Map<String, Object> login(String username, String password) {
        return login(username, password, "unknown");
    }

    public Map<String, Object> login(String username, String password, String clientIp) {
        if (username == null || password == null) throw new ApiException("用户名或密码错误");
        String lockKey = lockKey(clientIp, username);
        long now = System.currentTimeMillis();
        if (isLocked(lockKey, username, clientIp, now)) {
            throw new ApiException(429, "失败次数过多，账号已临时锁定，请稍后再试");
        }
        AdminUser user = adminUserMapper.selectOne(
                new QueryWrapper<AdminUser>().eq("username", username).last("LIMIT 1"));
        boolean matched = user != null && encoder.matches(password, user.passwordHash);
        if (!matched) {
            if (user == null) encoder.matches(password, DUMMY_HASH);
            recordLoginFailure(username, clientIp, now);
            throw new ApiException("用户名或密码错误");
        }
        if (user.status == null || user.status != 1) throw new ApiException("账号已禁用");
        clearLoginFailures(username, clientIp);
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

    private String lockKey(String ip, String username) {
        return ip + "|" + username;
    }

    private boolean isLocked(String lockKey, String username, String clientIp, long now) {
        if (redisTemplate != null) {
            try {
                Boolean locked = redisTemplate.hasKey("login:lock:" + lockKey);
                return Boolean.TRUE.equals(locked);
            } catch (Exception e) {
                // Redis 故障时降级到内存判定，登录不可用比防爆破更伤
            }
        }
        LoginState state = loginFailures.get(username + "@" + clientIp);
        return state != null && state.lockedUntil() > now;
    }

    private void recordLoginFailure(String username, String clientIp, long now) {
        String redisKey = "login:fail:" + lockKey(clientIp, username);
        if (redisTemplate != null) {
            try {
                Long count = redisTemplate.opsForValue().increment(redisKey);
                if (count != null) {
                    if (count == 1) {
                        redisTemplate.expire(redisKey, Duration.ofMillis(LOGIN_LOCK_MILLIS));
                    }
                    if (count >= MAX_LOGIN_FAILURES) {
                        redisTemplate.opsForValue().set("login:lock:" + lockKey(clientIp, username),
                                "1", Duration.ofMillis(LOGIN_LOCK_MILLIS));
                        redisTemplate.delete(redisKey);
                    }
                }
                return;
            } catch (Exception ignored) {
                // 降级到内存计数
            }
        }
        LoginState state = loginFailures.compute(username + "@" + clientIp, (k, old) ->
                new LoginState((old == null ? 0 : old.count()) + 1, old != null ? old.lockedUntil() : 0));
        if (state.count() >= MAX_LOGIN_FAILURES) {
            loginFailures.put(username + "@" + clientIp,
                    new LoginState(state.count(), now + LOGIN_LOCK_MILLIS));
        }
    }

    private void clearLoginFailures(String username, String clientIp) {
        if (redisTemplate != null) {
            try {
                redisTemplate.delete("login:fail:" + lockKey(clientIp, username));
                redisTemplate.delete("login:lock:" + lockKey(clientIp, username));
                return;
            } catch (Exception ignored) {
            }
        }
        loginFailures.remove(username + "@" + clientIp);
    }

    /** @throws ApiException(401) 若 token 签发时间早于最近一次改密 */
    public void assertTokenFresh(long adminId, long issuedAtMillis) {
        Long changed = passwordChangedAt.get(adminId);
        if (changed == null) {
            AdminTokenState state = tokenStateMapper == null ? null : tokenStateMapper.selectById(adminId);
            changed = state == null || state.passwordChangedAt == null ? 0L : state.passwordChangedAt;
            passwordChangedAt.put(adminId, changed);
        }
        // JWT iat 精度为秒，真实签发时间落在 [iat, iat+1s)，容差 1s 防止同秒内误杀
        if (changed > 0 && issuedAtMillis + 1000 <= changed) {
            throw new ApiException(401, "密码已修改，请重新登录");
        }
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
        long now = System.currentTimeMillis();
        passwordChangedAt.put(id, now);
        persistPasswordChangedAt(id, now);
    }

    private void persistPasswordChangedAt(long id, long now) {
        if (tokenStateMapper == null) return;
        AdminTokenState state = tokenStateMapper.selectById(id);
        if (state == null) {
            AdminTokenState fresh = new AdminTokenState();
            fresh.adminId = id;
            fresh.passwordChangedAt = now;
            try {
                tokenStateMapper.insert(fresh);
            } catch (DuplicateKeyException e) {
                tokenStateMapper.updateById(fresh);
            }
        } else {
            state.passwordChangedAt = now;
            tokenStateMapper.updateById(state);
        }
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
