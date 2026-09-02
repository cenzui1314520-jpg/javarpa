package com.rpa.server.config;

import com.rpa.server.common.ApiException;
import com.rpa.server.common.JwtUtil;
import com.rpa.server.service.AuthService;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

import java.util.Map;

@Configuration
@EnableWebSocketMessageBroker
public class StompConfig implements WebSocketMessageBrokerConfigurer {
    private final JwtUtil jwtUtil;
    private final AuthService authService;

    public StompConfig(JwtUtil jwtUtil, AuthService authService) {
        this.jwtUtil = jwtUtil;
        this.authService = authService;
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
                        if (token != null) {
                            try {
                                // 与 CONNECT 帧校验同标准：旧 token（改密前签发）不得经此通道续命
                                JwtUtil.AdminToken t = jwtUtil.verifyWithIssuedAt(token);
                                authService.assertTokenFresh(t.adminId(), t.issuedAtMillis());
                                attributes.put("adminId", t.adminId());
                            } catch (ApiException e) {
                                // 无效 token 不在握手层拒绝，由 CONNECT 帧校验兜底
                            }
                        }
                        return true;
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
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(
                        message, StompHeaderAccessor.class);
                if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())
                        && !authorized(accessor)) {
                    throw new IllegalArgumentException("STOMP CONNECT 未授权");
                }
                return message;
            }

            private boolean authorized(StompHeaderAccessor accessor) {
                String auth = accessor.getFirstNativeHeader("Authorization");
                if (auth != null && auth.startsWith("Bearer ")) {
                    try {
                        JwtUtil.AdminToken t = jwtUtil.verifyWithIssuedAt(auth.substring(7));
                        authService.assertTokenFresh(t.adminId(), t.issuedAtMillis());
                        return true;
                    } catch (ApiException ignored) {
                    }
                }
                // 兼容握手阶段经 URL token 验证过的旧客户端
                return accessor.getSessionAttributes() != null
                        && accessor.getSessionAttributes().containsKey("adminId");
            }
        });
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
    }
}
