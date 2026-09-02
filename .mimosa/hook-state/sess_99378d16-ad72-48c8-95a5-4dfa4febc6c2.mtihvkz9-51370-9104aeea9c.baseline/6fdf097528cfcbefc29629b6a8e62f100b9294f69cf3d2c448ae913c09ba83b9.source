package com.rpa.server.security;

import com.rpa.server.entity.Device;
import com.rpa.server.service.DeviceService;
import com.rpa.server.service.ScriptService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates device credentials + per-script-version authorization for /files/scripts/**. */
@Component
public class DeviceAuthInterceptor implements HandlerInterceptor {
    // /files/scripts/{pkgName}/{versionCode}.zip
    private static final Pattern SCRIPT_ZIP = Pattern.compile("^/files/scripts/([a-zA-Z0-9._-]+)/(\\d+)\\.zip$");

    private final DeviceService deviceService;
    private final ScriptService scriptService;

    public DeviceAuthInterceptor(DeviceService deviceService, ScriptService scriptService) {
        this.deviceService = deviceService;
        this.scriptService = scriptService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String sn = request.getHeader("X-Device-Sn");
        String secret = request.getHeader("X-Device-Secret");
        Device device = deviceService.authenticate(sn, secret);
        if (device == null) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"msg\":\"设备鉴权失败\"}");
            return false;
        }
        Matcher m = SCRIPT_ZIP.matcher(request.getRequestURI());
        if (m.matches()) {
            // 灰度未发布的版本不允许任意设备遍历下载
            boolean allowed = scriptService.canDeviceDownload(device, m.group(1),
                    Integer.parseInt(m.group(2)));
            if (!allowed) {
                response.setStatus(403);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":403,\"msg\":\"设备无权下载该脚本版本\"}");
                return false;
            }
        }
        return true;
    }
}
