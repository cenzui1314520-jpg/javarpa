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
    private static final String PROCESSING_SUFFIX = ":processing";
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

    /** 崩溃恢复：processing 中未 ACK 的命令按原顺序回灌待发队列头部。 */
    public void recoverProcessing(long deviceId) {
        try {
            String src = KEY_PREFIX + deviceId + PROCESSING_SUFFIX;
            String dst = KEY_PREFIX + deviceId;
            for (int i = 0; i < 200; i++) {
                String json = redis.opsForList().rightPop(src);
                if (json == null) break;
                redis.opsForList().leftPush(dst, json);
            }
        } catch (Exception e) {
            log.error("recover processing for device {} failed: {}", deviceId, e.getMessage());
        }
    }

    /** LMOVE 到 processing 队列后返回待发送命令（最多 50 条），发送结果用 ack/nack 结算。 */
    public List<WsMessage> drainPending(long deviceId) {
        List<WsMessage> result = new ArrayList<>();
        try {
            String src = KEY_PREFIX + deviceId;
            String processing = src + PROCESSING_SUFFIX;
            for (int i = 0; i < 50; i++) {
                // 签名为 move(sourceKey, from, destinationKey, to)：队头取出、队尾入 processing，保持 FIFO；未 ACK 前不丢
                String json = redis.opsForList().move(src,
                        org.springframework.data.redis.connection.RedisListCommands.Direction.LEFT,
                        processing,
                        org.springframework.data.redis.connection.RedisListCommands.Direction.RIGHT);
                if (json == null) break;
                result.add(MAPPER.readValue(json, WsMessage.class));
            }
        } catch (Exception e) {
            log.error("drain pending for device {} failed: {}", deviceId, e.getMessage());
        }
        return result;
    }

    /** 发送成功后确认，从 processing 队列移除。 */
    public void ackPending(long deviceId, WsMessage msg) {
        try {
            redis.opsForList().remove(KEY_PREFIX + deviceId + PROCESSING_SUFFIX, 1, json(msg));
        } catch (Exception e) {
            log.warn("ack pending for device {} failed: {}", deviceId, e.getMessage());
        }
    }

    /** 发送失败回滚：命令重回待发队列尾部，避免静默丢失。 */
    public void nackPending(long deviceId, WsMessage msg) {
        try {
            String json = json(msg);
            redis.opsForList().rightPush(KEY_PREFIX + deviceId, json);
            redis.opsForList().remove(KEY_PREFIX + deviceId + PROCESSING_SUFFIX, 1, json);
        } catch (Exception e) {
            log.error("nack pending for device {} failed: {}", deviceId, e.getMessage());
        }
    }

    private String json(WsMessage msg) {
        try {
            return MAPPER.writeValueAsString(msg);
        } catch (Exception e) {
            return "";
        }
    }
}
