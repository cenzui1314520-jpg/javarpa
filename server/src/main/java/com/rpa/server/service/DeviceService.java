package com.rpa.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rpa.server.common.ApiException;
import com.rpa.server.common.DigestUtil;
import com.rpa.server.entity.Device;
import com.rpa.server.entity.DeviceGroup;
import com.rpa.server.entity.DeviceGroupMember;
import com.rpa.server.mapper.DeviceGroupMapper;
import com.rpa.server.mapper.DeviceGroupMemberMapper;
import com.rpa.server.mapper.DeviceMapper;
import com.rpa.server.ws.AdminStompService;
import com.rpa.server.ws.DeviceSessionManager;
import com.rpa.server.ws.RedisQueueService;
import com.rpa.server.ws.WsMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DeviceService {
    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final DeviceMapper deviceMapper;
    private final DeviceGroupMapper deviceGroupMapper;
    private final DeviceGroupMemberMapper memberMapper;
    private final DeviceSessionManager sessionManager;
    private final RedisQueueService redisQueue;
    private final AdminStompService stomp;
    private final DeviceLogService deviceLogService;
    private final PublishService publishService;
    private final TaskControlService taskControlService;

    @Value("${rpa.heartbeat-timeout-seconds:90}")
    private int heartbeatTimeoutSeconds;

    public DeviceService(DeviceMapper deviceMapper, DeviceGroupMapper deviceGroupMapper,
                         DeviceGroupMemberMapper memberMapper, DeviceSessionManager sessionManager,
                         RedisQueueService redisQueue, AdminStompService stomp,
                         DeviceLogService deviceLogService, PublishService publishService,
                         TaskControlService taskControlService) {
        this.deviceMapper = deviceMapper;
        this.deviceGroupMapper = deviceGroupMapper;
        this.memberMapper = memberMapper;
        this.sessionManager = sessionManager;
        this.redisQueue = redisQueue;
        this.stomp = stomp;
        this.deviceLogService = deviceLogService;
        this.publishService = publishService;
        this.taskControlService = taskControlService;
    }

    public Device authenticate(String deviceSn, String secret) {
        if (deviceSn == null || secret == null) return null;
        Device device = deviceMapper.selectOne(
                new QueryWrapper<Device>().eq("device_sn", deviceSn).last("LIMIT 1"));
        if (device == null || device.status == null || device.status != 1) return null;
        String stored = device.secret;
        if (stored == null) return null;
        boolean ok;
        boolean needsUpgrade;
        if (stored.startsWith("$2")) {
            // BCrypt（60 字符，$2a$ 前缀），列宽 64 可容纳，无需 schema 变更
            ok = bcrypt.matches(secret, stored);
            needsUpgrade = false;
        } else if (stored.length() == 64 && stored.chars().allMatch(c ->
                (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
            // 历史 SHA-256（无盐）兼容比对
            ok = MessageDigest.isEqual(stored.getBytes(), DigestUtil.sha256Hex(secret).getBytes());
            needsUpgrade = true;
        } else {
            // 更早期的明文 secret 兼容比对
            ok = MessageDigest.isEqual(stored.getBytes(), secret.getBytes());
            needsUpgrade = true;
        }
        if (!ok) return null;
        if (needsUpgrade) {
            // 认证成功即透明升级为 BCrypt，存量凭据逐步收敛
            Device upd = new Device();
            upd.id = device.id;
            upd.secret = bcrypt.encode(secret);
            deviceMapper.updateById(upd);
        }
        return device;
    }

    public Device create(String deviceSn, String name, Long groupId) {
        if (deviceSn == null || deviceSn.isBlank()) throw new ApiException("deviceSn 不能为空");
        Long exists = deviceMapper.selectCount(new QueryWrapper<Device>().eq("device_sn", deviceSn));
        if (exists > 0) throw new ApiException("设备编号已存在");
        Device d = new Device();
        d.deviceSn = deviceSn;
        d.name = name;
        d.groupId = groupId;
        String rawSecret = DigestUtil.randomToken(32);
        d.secret = bcrypt.encode(rawSecret); // 落库只存 BCrypt 哈希
        d.status = 1;
        d.online = 0;
        deviceMapper.insert(d);
        if (groupId != null && groupId > 0) addMember(groupId, d.id);
        d.secret = rawSecret; // 明文仅本次返回给调用方
        return d;
    }

    public String resetSecret(long id) {
        Device d = require(id);
        String rawSecret = DigestUtil.randomToken(32);
        Device upd = new Device();
        upd.id = d.id;
        upd.secret = bcrypt.encode(rawSecret);
        deviceMapper.updateById(upd);
        sessionManager.forceClose(String.valueOf(id));
        return rawSecret;
    }

    public void update(long id, String name, Long groupId, Integer status) {
        Device d = require(id);
        Device upd = new Device();
        upd.id = id;
        upd.name = name;
        upd.status = status;
        if (groupId != null && !groupId.equals(d.groupId)) {
            upd.groupId = groupId;
            memberMapper.delete(new QueryWrapper<DeviceGroupMember>().eq("device_id", id));
            if (groupId > 0) addMember(groupId, id);
        }
        deviceMapper.updateById(upd);
        if (status != null && status == 0) {
            sessionManager.forceClose(String.valueOf(id)); // 禁用立即断开现有连接
        }
    }

    public void delete(long id) {
        deviceMapper.deleteById(id);
        memberMapper.delete(new QueryWrapper<DeviceGroupMember>().eq("device_id", id));
        sessionManager.forceClose(String.valueOf(id));
    }

    public Map<String, Object> page(String keyword, Long groupId, Integer online, int page, int size) {
        QueryWrapper<Device> qw = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like("device_sn", keyword).or().like("name", keyword));
        }
        if (groupId != null && groupId > 0) qw.eq("group_id", groupId);
        if (online != null) qw.eq("online", online);
        qw.orderByDesc("id");
        Page<Device> p = deviceMapper.selectPage(Page.of(page, size), qw);
        Map<Long, String> groupNames = groupNames();
        p.getRecords().forEach(d -> d.secret = null);
        Map<String, Object> result = new HashMap<>();
        result.put("total", p.getTotal());
        result.put("pages", p.getPages());
        result.put("list", p.getRecords().stream().map(d -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", d.id);
            m.put("deviceSn", d.deviceSn);
            m.put("name", d.name);
            m.put("groupId", d.groupId);
            m.put("groupName", groupNames.get(d.groupId));
            m.put("model", d.model);
            m.put("brand", d.brand);
            m.put("androidVersion", d.androidVersion);
            m.put("sdkInt", d.sdkInt);
            m.put("appVersion", d.appVersion);
            m.put("engineVersion", d.engineVersion);
            m.put("status", d.status);
            m.put("online", d.online);
            m.put("lastActiveAt", d.lastActiveAt);
            m.put("createdAt", d.createdAt);
            return m;
        }).toList());
        return result;
    }

    public Device require(long id) {
        Device d = deviceMapper.selectById(id);
        if (d == null) throw new ApiException(404, "设备不存在");
        return d;
    }

    private Map<Long, String> groupNames() {
        Map<Long, String> map = new HashMap<>();
        deviceGroupMapper.selectList(null).forEach(g -> map.put(g.id, g.name));
        return map;
    }

    private void addMember(long groupId, long deviceId) {
        DeviceGroupMember m = new DeviceGroupMember();
        m.groupId = groupId;
        m.deviceId = deviceId;
        memberMapper.insert(m);
    }

    // ---------- WebSocket message handling ----------

    public void handleRegister(String deviceId, Map<String, Object> data, WebSocketSession session) {
        long id = Long.parseLong(deviceId);
        Device upd = new Device();
        upd.id = id;
        if (data.get("deviceName") != null) upd.name = str(data, "deviceName");
        if (data.get("model") != null) upd.model = str(data, "model");
        if (data.get("brand") != null) upd.brand = str(data, "brand");
        if (data.get("androidVersion") != null) upd.androidVersion = str(data, "androidVersion");
        if (data.get("sdkInt") != null) upd.sdkInt = num(data, "sdkInt").intValue();
        if (data.get("appVersion") != null) upd.appVersion = str(data, "appVersion");
        if (data.get("engineVersion") != null) upd.engineVersion = str(data, "engineVersion");
        upd.online = 1;
        upd.lastActiveAt = LocalDateTime.now();
        deviceMapper.updateById(upd);

        Map<String, Object> ack = new HashMap<>();
        ack.put("ok", true);
        ack.put("serverTime", System.currentTimeMillis());
        sessionManager.send(deviceId, WsMessage.of("REGISTER_ACK", ack));

        Device device = deviceMapper.selectById(id);
        if (device != null) {
            pushScriptUpdates(device, data.get("installedVersions"));
        }
        for (WsMessage pending : redisQueue.drainPending(id)) {
            sessionManager.send(deviceId, pending);
        }
        stomp.pushDeviceStatus(id, true);
    }

    @SuppressWarnings("unchecked")
    private void pushScriptUpdates(Device device, Object installedVersions) {
        Map<Long, Integer> installed = new HashMap<>();
        if (installedVersions instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("scriptId") != null) {
                    installed.put(num((Map<String, Object>) m, "scriptId").longValue(),
                            num((Map<String, Object>) m, "versionCode").intValue());
                }
            }
        }
        for (WsMessage update : publishService.updatesForDevice(device, installed)) {
            sessionManager.send(String.valueOf(device.id), update);
        }
    }

    public void handleHeartbeat(String deviceId, Map<String, Object> data) {
        long id = Long.parseLong(deviceId);
        Device upd = new Device();
        upd.id = id;
        upd.online = 1;
        upd.lastActiveAt = LocalDateTime.now();
        deviceMapper.updateById(upd);

        Long taskId = data.get("taskId") == null ? null : num(data, "taskId").longValue();
        if (taskId != null && Boolean.TRUE.equals(data.get("running"))) {
            taskControlService.updateProgress(taskId, id,
                    num(data, "successCount").intValue(), num(data, "failCount").intValue());
        }
        Map<String, Object> ack = new HashMap<>();
        ack.put("serverTime", System.currentTimeMillis());
        sessionManager.send(deviceId, WsMessage.of("HEARTBEAT_ACK", ack));
    }

    public void handleLog(String deviceId, Map<String, Object> data) {
        deviceLogService.onDeviceLog(Long.parseLong(deviceId), data);
    }

    public void markOffline(String deviceId) {
        try {
            long id = Long.parseLong(deviceId);
            Device upd = new Device();
            upd.id = id;
            upd.online = 0;
            deviceMapper.updateById(upd);
            stomp.pushDeviceStatus(id, false);
        } catch (NumberFormatException e) {
            log.warn("invalid deviceId {}", deviceId);
        }
    }

    @Scheduled(fixedDelayString = "${rpa.offline-scan-seconds:30}s", initialDelayString = "${rpa.offline-scan-seconds:30}s")
    public void scanOffline() {
        LocalDateTime deadline = LocalDateTime.now().minusSeconds(heartbeatTimeoutSeconds);
        List<Device> stale = deviceMapper.selectList(
                new QueryWrapper<Device>().eq("online", 1).lt("last_active_at", deadline));
        for (Device d : stale) {
            log.info("device {} heartbeat timeout, mark offline", d.deviceSn);
            sessionManager.forceClose(String.valueOf(d.id));
            markOffline(String.valueOf(d.id));
        }
    }

    private static String str(Map<String, Object> data, String key) {
        Object v = data.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Number num(Map<String, Object> data, String key) {
        Object v = data.get(key);
        if (v instanceof Number n) return n;
        if (v == null) return 0;
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0; // 字段非法不应中断心跳 ACK
        }
    }
}
