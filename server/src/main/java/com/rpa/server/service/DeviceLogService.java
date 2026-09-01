package com.rpa.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rpa.server.entity.DeviceLog;
import com.rpa.server.mapper.DeviceLogMapper;
import com.rpa.server.ws.AdminStompService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

@Service
public class DeviceLogService {
    private static final Logger log = LoggerFactory.getLogger(DeviceLogService.class);

    private final DeviceLogMapper deviceLogMapper;
    private final AdminStompService stomp;
    private final Executor logExecutor;

    @Value("${rpa.log-retention-days:30}")
    private int retentionDays;

    public DeviceLogService(DeviceLogMapper deviceLogMapper, AdminStompService stomp,
                            @Qualifier("logExecutor") Executor logExecutor) {
        this.deviceLogMapper = deviceLogMapper;
        this.stomp = stomp;
        this.logExecutor = logExecutor;
    }

    public void onDeviceLog(long deviceId, Map<String, Object> data) {
        Map<String, Object> push = new HashMap<>(data);
        push.put("deviceId", deviceId);
        push.put("recvTime", System.currentTimeMillis());
        stomp.pushDeviceLog(deviceId, push);

        DeviceLog entry = new DeviceLog();
        entry.deviceId = deviceId;
        entry.taskId = parseTaskId(data.get("taskId"));
        entry.level = data.get("level") == null ? "INFO" : String.valueOf(data.get("level"));
        entry.tag = data.get("tag") == null ? null : String.valueOf(data.get("tag"));
        entry.content = data.get("content") == null ? null : String.valueOf(data.get("content"));
        if (entry.content != null && entry.content.length() > 2000) {
            entry.content = entry.content.substring(0, 2000);
        }
        entry.logTime = LocalDateTime.now();
        // 高频日志走异步线程落库，避免拖慢 WS 消息处理
        logExecutor.execute(() -> {
            try {
                deviceLogMapper.insert(entry);
            } catch (Exception e) {
                log.warn("save device log failed: {}", e.getMessage());
            }
        });
    }

    private static Long parseTaskId(Object v) {
        if (v == null) return null;
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Map<String, Object> page(Long deviceId, Long taskId, String level, int page, int size) {
        QueryWrapper<DeviceLog> qw = new QueryWrapper<>();
        if (deviceId != null) qw.eq("device_id", deviceId);
        if (taskId != null) qw.eq("task_id", taskId);
        if (level != null && !level.isBlank()) qw.eq("level", level);
        qw.orderByDesc("id");
        Page<DeviceLog> p = deviceLogMapper.selectPage(Page.of(page, size), qw);
        Map<String, Object> result = new HashMap<>();
        result.put("total", p.getTotal());
        result.put("pages", p.getPages());
        result.put("list", p.getRecords());
        return result;
    }

    @Scheduled(cron = "0 30 3 * * ?")
    public void cleanupExpired() {
        LocalDateTime deadline = LocalDateTime.now().minusDays(retentionDays);
        deviceLogMapper.delete(new QueryWrapper<DeviceLog>().lt("created_at", deadline));
        log.info("expired device logs before {} cleaned", deadline);
    }
}
