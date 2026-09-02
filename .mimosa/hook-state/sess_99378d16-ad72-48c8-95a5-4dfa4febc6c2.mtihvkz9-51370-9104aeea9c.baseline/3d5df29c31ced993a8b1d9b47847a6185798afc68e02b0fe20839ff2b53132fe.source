package com.rpa.server.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Set;

@Component
public class JwtUtil {
    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);
    private static final Set<String> KNOWN_DEFAULT_SECRETS = Set.of(
            "change-me-in-production-0123456789abcdef");

    private final SecretKey key;
    private final long expireMillis;

    public JwtUtil(@Value("${rpa.jwt-secret}") String secret,
                   @Value("${rpa.jwt-expire-hours:24}") int expireHours,
                   @Value("${spring.profiles.active:dev}") String activeProfile) {
        if (secret == null || secret.isBlank() || KNOWN_DEFAULT_SECRETS.contains(secret)) {
            boolean dev = activeProfile == null || activeProfile.isBlank()
                    || activeProfile.equalsIgnoreCase("dev")
                    || activeProfile.equalsIgnoreCase("local");
            if (!dev) {
                throw new IllegalStateException(
                        "检测到 JWT 密钥为默认值，生产环境必须通过环境变量 RPA_JWT_SECRET 设置强随机密钥");
            }
            log.warn("JWT secret 为默认值（仅限开发环境），生产环境请设置 RPA_JWT_SECRET");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMillis = expireHours * 3600_000L;
    }

    public String issue(long adminId, String username) {
        return Jwts.builder()
                .subject(username)
                .claim("id", adminId)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expireMillis))
                .signWith(key)
                .compact();
    }

    /** @return adminId, throws ApiException when token invalid/expired */
    public long verify(String token) {
        return parse(token).adminId();
    }

    /** @return adminId 与签发时间（毫秒），用于改密后旧 token 失效判断 */
    public AdminToken verifyWithIssuedAt(String token) {
        return parse(token);
    }

    private AdminToken parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            Number id = claims.get("id", Number.class);
            if (id == null) throw new ApiException(401, "无效 token");
            Date iat = claims.getIssuedAt();
            return new AdminToken(id.longValue(), iat == null ? 0L : iat.getTime());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(401, "token 无效或已过期");
        }
    }

    public record AdminToken(long adminId, long issuedAtMillis) {}
}
