package com.rpa.server.config;

import com.rpa.server.common.ApiException;
import com.rpa.server.common.JwtUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

import java.util.Map;

@Configuration
@EnableWebSocketMessageBroker
public class StompConfig implements WebSocketMessageBrokerConfigurer {
    private final JwtUtil jwtUtil;

    public StompConfig(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/admin")
                .setAllowedOriginPatterns("*")
                .addInterceptors(new HttpSessionHandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(org.springframework.http.server.ServerHttpRequest request,
                                                   org.springframework.http.server.ServerHttpResponse response,
                                                   org.springframework.web.socket.WebSocketHandler wsHandler,
                                                   Map<String, Object> attributes) {
                        String token = tokenFromQuery(request);
                        if (token == null) return false;
                        try {
                            attributes.put("adminId", jwtUtil.verify(token));
                            return true;
                        } catch (ApiException e) {
                            return false;
                        }
                    }
                });
    }

    private String tokenFromQuery(org.springframework.http.server.ServerHttpRequest request) {
        String query = request.getURI().getQuery();
        if (query == null) return null;
        for (String kv : query.split("&")) {
            if (kv.startsWith("token=")) return kv.substring(6);
        }
        return null;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
    }
}
