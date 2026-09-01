package com.rpa.server.security;

import com.rpa.server.common.ApiException;
import com.rpa.server.common.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Validates admin JWT for management APIs. */
@Component
public class JwtInterceptor implements HandlerInterceptor {
    private final JwtUtil jwtUtil;

    public JwtInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return reject(response);
        }
        try {
            long adminId = jwtUtil.verify(auth.substring(7));
            request.setAttribute("adminId", adminId);
            return true;
        } catch (ApiException e) {
            return reject(response);
        }
    }

    private boolean reject(HttpServletResponse response) throws Exception {
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"msg\":\"未登录或token已过期\"}");
        return false;
    }
}
