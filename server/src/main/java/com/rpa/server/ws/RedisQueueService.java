package com.rpa.server.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Pending command queue for offline devices, backed by Redis list. */
@Service
public class RedisQueueService {
    private static final Logger log = LoggerFactory.getLogger(RedisQueueService.class);
    private static final String KEY_PREFIX = "rpa:pending:";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StringRedisTemplate redis;

    public RedisQueueService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void pushPending(long deviceId, WsMessage msg) {
        try {
            ListOperations<String, String> ops = redis.opsForList();
            ops.rightPush(KEY_PREFIX + deviceId, MAPPER.writeValueAsString(msg));
            redis.expire(KEY_PREFIX + deviceId, Duration.ofDays(7));
        } catch (Exception e) {
            log.error("push pending for device {} failed: {}", deviceId, e.getMessage());
        }
    }

    /** Drains queued commands for a device, up to 50 items. */
    public List<WsMessage> drainPending(long deviceId) {
        List<WsMessage> result = new ArrayList<>();
        try {
            String key = KEY_PREFIX + deviceId;
            for (int i = 0; i < 50; i++) {
                String json = redis.opsForList().leftPop(key);
                if (json == null) break;
                result.add(MAPPER.readValue(json, WsMessage.class));
            }
        } catch (Exception e) {
            log.error("drain pending for device {} failed: {}", deviceId, e.getMessage());
        }
        return result;
    }

    public void markOnline(long deviceId) {
        try {
            redis.opsForValue().set("rpa:online:" + deviceId, "1", Duration.ofMinutes(5));
        } catch (Exception ignored) {}
    }
}
