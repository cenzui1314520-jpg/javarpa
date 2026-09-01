package com.rpa.server.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Pushes real-time events to admin console via STOMP topics. */
@Service
public class AdminStompService {
    private static final Logger log = LoggerFactory.getLogger(AdminStompService.class);

    private final SimpMessagingTemplate template;

    public AdminStompService(SimpMessagingTemplate template) {
        this.template = template;
    }

    public void pushDeviceLog(long deviceId, Map<String, Object> logData) {
        safeSend("/topic/device/" + deviceId + "/logs", logData);
    }

    public void pushDeviceStatus(long deviceId, boolean online) {
        safeSend("/topic/device/status", Map.of("deviceId", deviceId, "online", online));
    }

    public void pushTaskDeviceStatus(long taskId, Map<String, Object> statusData) {
        safeSend("/topic/task/" + taskId + "/status", statusData);
    }

    private void safeSend(String topic, Object payload) {
        try {
            template.convertAndSend(topic, payload);
        } catch (Exception e) {
            log.debug("stomp push {} failed: {}", topic, e.getMessage());
        }
    }
}
