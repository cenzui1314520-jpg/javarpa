package com.rpa.server.security;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpa.server.common.DigestUtil;
import com.rpa.server.entity.ApiToken;
import com.rpa.server.mapper.ApiTokenMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDateTime;

/** Validates X-API-Token for open APIs (/open/v1/**). */
@Component
public class ApiTokenInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(ApiTokenInterceptor.class);

    private final ApiTokenMapper apiTokenMapper;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApiTokenInterceptor(ApiTokenMapper apiTokenMapper) {
        this.apiTokenMapper = apiTokenMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String token = request.getHeader("X-API-Token");
        if (token == null || token.isBlank()) return reject(response);
        String hash = DigestUtil.sha256Hex(token);
        ApiToken found = apiTokenMapper.selectOne(
                new QueryWrapper<ApiToken>().eq("token_hash", hash).last("LIMIT 1"));
        if (found == null || found.status == null || found.status != 1) return reject(response);
        request.setAttribute("apiTokenId", found.id);
        touchLastUsed(found.id);
        return true;
    }

    private void touchLastUsed(long id) {
        try {
            ApiToken t = new ApiToken();
            t.id = id;
            t.lastUsedAt = LocalDateTime.now();
            apiTokenMapper.updateById(t);
        } catch (Exception e) {
            log.debug("touch token failed: {}", e.getMessage());
        }
    }

    private boolean reject(HttpServletResponse response) throws Exception {
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"msg\":\"无效的API Token\"}");
        return false;
    }
}
