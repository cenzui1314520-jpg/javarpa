package com.rpa.server.config;

import com.rpa.server.ws.DeviceWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final DeviceWebSocketHandler deviceWebSocketHandler;
    private final DeviceHandshakeInterceptor handshakeInterceptor;

    public WebSocketConfig(DeviceWebSocketHandler handler, DeviceHandshakeInterceptor interceptor) {
        this.deviceWebSocketHandler = handler;
        this.handshakeInterceptor = interceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(deviceWebSocketHandler, "/ws/device")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins("*");
    }
}
