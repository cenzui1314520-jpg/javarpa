package com.rpa.server.ws;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpa.server.service.DeviceService;
import com.rpa.server.service.TaskControlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;

@Component
public class DeviceWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(DeviceWebSocketHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final DeviceService deviceService;
    private final TaskControlService taskControlService;
    private final DeviceSessionManager sessionManager;

    public DeviceWebSocketHandler(DeviceService deviceService,
                                  TaskControlService taskControlService,
                                  DeviceSessionManager sessionManager) {
        this.deviceService = deviceService;
        this.taskControlService = taskControlService;
        this.sessionManager = sessionManager;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String deviceId = deviceId(session);
        if (deviceId == null) {
            close(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        sessionManager.register(deviceId, session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String deviceId = deviceId(session);
        if (deviceId == null) {
            close(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        WsMessage msg;
        try {
            msg = MAPPER.readValue(message.getPayload(), WsMessage.class);
        } catch (Exception e) {
            log.warn("bad message from {}: {}", deviceId, e.getMessage());
            return;
        }
        try {
            switch (msg.type == null ? "" : msg.type) {
                case "REGISTER" -> deviceService.handleRegister(deviceId, msg.data, session);
                case "HEARTBEAT" -> deviceService.handleHeartbeat(deviceId, msg.data);
                case "LOG" -> deviceService.handleLog(deviceId, msg.data);
                case "RESULT" -> taskControlService.handleResult(Long.parseLong(deviceId), msg.data);
                case "ACK" -> log.debug("device {} ack {}: ok={}", deviceId,
                        msg.data != null ? msg.data.get("refMsgId") : "?",
                        msg.data != null ? msg.data.get("ok") : "?");
                default -> log.warn("unknown message type {} from {}", msg.type, deviceId);
            }
        } catch (Exception e) {
            log.error("handle message {} from {} failed", msg.type, deviceId, e);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable error) {
        cleanup(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cleanup(session);
    }

    private void cleanup(WebSocketSession session) {
        String deviceId = deviceId(session);
        if (deviceId != null) {
            sessionManager.unregister(deviceId, session);
            deviceService.markOffline(deviceId);
        }
    }

    private String deviceId(WebSocketSession session) {
        Object id = session.getAttributes().get("deviceId");
        return id == null ? null : String.valueOf(id);
    }

    private void close(WebSocketSession session, CloseStatus status) {
        try { session.close(status); } catch (IOException ignored) {}
    }
}
