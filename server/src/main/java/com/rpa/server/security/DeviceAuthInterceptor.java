package com.rpa.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpa.server.service.DeviceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Validates device credentials for script package downloads (/files/**). */
@Component
public class DeviceAuthInterceptor implements HandlerInterceptor {
    private final DeviceService deviceService;
    private final ObjectMapper mapper = new ObjectMapper();

    public DeviceAuthInterceptor(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String sn = request.getHeader("X-Device-Sn");
        String secret = request.getHeader("X-Device-Secret");
        if (deviceService.authenticate(sn, secret) == null) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"msg\":\"设备鉴权失败\"}");
            return false;
        }
        return true;
    }
}
