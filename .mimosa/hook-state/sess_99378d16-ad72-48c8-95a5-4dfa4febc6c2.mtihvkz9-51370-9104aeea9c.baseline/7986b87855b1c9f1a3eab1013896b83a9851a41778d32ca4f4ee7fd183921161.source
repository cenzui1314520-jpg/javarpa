package com.rpa.server.config;

import com.rpa.server.entity.Device;
import com.rpa.server.service.DeviceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Component
public class DeviceHandshakeInterceptor implements HandshakeInterceptor {
    private final DeviceService deviceService;

    public DeviceHandshakeInterceptor(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String deviceSn = firstHeader(request, "X-Device-Id");
        String secret = firstHeader(request, "X-Device-Secret");
        Device device = deviceService.authenticate(deviceSn, secret);
        if (device == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put("deviceId", device.id);
        attributes.put("deviceSn", device.deviceSn);
        return true;
    }

    private String firstHeader(ServerHttpRequest request, String name) {
        var values = request.getHeaders().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
