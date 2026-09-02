package com.rpa.server.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DeviceSessionManager {
    private static final Logger log = LoggerFactory.getLogger(DeviceSessionManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public void register(String deviceId, WebSocketSession session) {
        WebSocketSession old = sessions.put(deviceId, session);
        if (old != null && old.isOpen()) {
            try { old.close(); } catch (IOException ignored) {}
        }
        log.info("device online: {} (total {})", deviceId, sessions.size());
    }

    /** @return true 若被移除的 session 仍是当前注册者（未被新连接顶替） */
    public boolean unregister(String deviceId, WebSocketSession session) {
        boolean wasCurrent = sessions.remove(deviceId, session);
        log.info("device offline: {} (total {})", deviceId, sessions.size());
        return wasCurrent;
    }

    public boolean isOnline(String deviceId) {
        WebSocketSession s = sessions.get(deviceId);
        return s != null && s.isOpen();
    }

    public void forceClose(String deviceId) {
        WebSocketSession s = sessions.remove(deviceId);
        if (s != null && s.isOpen()) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    public int count() {
        return (int) sessions.values().stream().filter(WebSocketSession::isOpen).count();
    }

    public boolean send(String deviceId, WsMessage msg) {
        WebSocketSession s = sessions.get(deviceId);
        if (s == null || !s.isOpen()) return false;
        try {
            String payload = MAPPER.writeValueAsString(msg);
            synchronized (s) {
                s.sendMessage(new TextMessage(payload));
            }
            return true;
        } catch (IOException e) {
            log.warn("send to device {} failed: {}", deviceId, e.getMessage());
            return false;
        }
    }
}
