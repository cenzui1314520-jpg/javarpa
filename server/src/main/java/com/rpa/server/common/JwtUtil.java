package com.rpa.server.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {
    private final SecretKey key;
    private final long expireMillis;

    public JwtUtil(@Value("${rpa.jwt-secret}") String secret,
                   @Value("${rpa.jwt-expire-hours:24}") int expireHours) {
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
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            Number id = claims.get("id", Number.class);
            if (id == null) throw new ApiException(401, "无效 token");
            return id.longValue();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(401, "token 无效或已过期");
        }
    }
}
