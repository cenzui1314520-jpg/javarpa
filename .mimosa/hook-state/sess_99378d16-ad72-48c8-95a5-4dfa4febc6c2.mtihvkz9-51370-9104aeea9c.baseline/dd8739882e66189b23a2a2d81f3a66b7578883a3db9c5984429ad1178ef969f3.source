package com.rpa.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rpa.server.common.ApiException;
import com.rpa.server.entity.Device;
import com.rpa.server.entity.DeviceGroupMember;
import com.rpa.server.entity.PublishRecord;
import com.rpa.server.entity.Script;
import com.rpa.server.entity.ScriptVersion;
import com.rpa.server.mapper.DeviceGroupMemberMapper;
import com.rpa.server.mapper.DeviceMapper;
import com.rpa.server.mapper.PublishRecordMapper;
import com.rpa.server.mapper.ScriptMapper;
import com.rpa.server.mapper.ScriptVersionMapper;
import com.rpa.server.ws.DeviceSessionManager;
import com.rpa.server.ws.WsMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Script publishing with gray-release (ALL / GROUP / PERCENT) and rollback. */
@Service
public class PublishService {
    private static final Logger log = LoggerFactory.getLogger(PublishService.class);

    private final ScriptMapper scriptMapper;
    private final ScriptVersionMapper versionMapper;
    private final PublishRecordMapper publishRecordMapper;
    private final DeviceMapper deviceMapper;
    private final DeviceGroupMemberMapper memberMapper;
    private final DeviceSessionManager sessionManager;

    public PublishService(ScriptMapper scriptMapper, ScriptVersionMapper versionMapper,
                          PublishRecordMapper publishRecordMapper, DeviceMapper deviceMapper,
                          DeviceGroupMemberMapper memberMapper, DeviceSessionManager sessionManager) {
        this.scriptMapper = scriptMapper;
        this.versionMapper = versionMapper;
        this.publishRecordMapper = publishRecordMapper;
        this.deviceMapper = deviceMapper;
        this.memberMapper = memberMapper;
        this.sessionManager = sessionManager;
    }

    public void publish(long scriptId, int versionCode, String targetType, String targetValue, String operator) {
        Script script = scriptMapper.selectById(scriptId);
        if (script == null) throw new ApiException(404, "脚本不存在");
        ScriptVersion version = versionMapper.selectOne(new QueryWrapper<ScriptVersion>()
                .eq("script_id", scriptId).eq("version_code", versionCode).last("LIMIT 1"));
        if (version == null) throw new ApiException(404, "脚本版本不存在");
        validateTarget(targetType, targetValue);

        PublishRecord record = new PublishRecord();
        record.scriptId = scriptId;
        record.versionCode = versionCode;
        record.targetType = targetType;
        record.targetValue = targetValue;
        record.operator = operator;
        publishRecordMapper.insert(record);

        if ("ALL".equals(targetType)) {
            Script upd = new Script();
            upd.id = scriptId;
            upd.stableVersionCode = versionCode;
            scriptMapper.updateById(upd);
        }
        pushToMatchingDevices(script, version, targetType, targetValue);
        log.info("script {} v{} published to {}({}) by {}", scriptId, versionCode, targetType, targetValue, operator);
    }

    private void validateTarget(String targetType, String targetValue) {
        switch (targetType) {
            case "ALL" -> { /* no value */ }
            case "GROUP" -> {
                if (targetValue == null || targetValue.isBlank()) throw new ApiException("GROUP 发布需指定分组");
            }
            case "PERCENT" -> {
                int pct;
                try { pct = Integer.parseInt(targetValue); } catch (NumberFormatException e) {
                    throw new ApiException("PERCENT 发布需指定 0-100 的整数");
                }
                if (pct < 0 || pct > 100) throw new ApiException("PERCENT 需在 0-100 之间");
            }
            default -> throw new ApiException("targetType 必须为 ALL/GROUP/PERCENT");
        }
    }

    private void pushToMatchingDevices(Script script, ScriptVersion version, String targetType, String targetValue) {
        List<Device> devices = deviceMapper.selectList(
                new QueryWrapper<Device>().eq("status", 1).eq("online", 1));
        for (Device d : devices) {
            if (matches(d, targetType, targetValue)) {
                sessionManager.send(String.valueOf(d.id), updateMessage(script.id, version));
            }
        }
    }

    private WsMessage updateMessage(long scriptId, ScriptVersion version) {
        Map<String, Object> data = new HashMap<>();
        data.put("scriptId", scriptId);
        data.put("versionCode", version.versionCode);
        data.put("url", version.filePath);
        data.put("md5", version.fileMd5);
        return WsMessage.of("CMD_UPDATE_SCRIPT", data);
    }

    /** Device-target version = newest matching publish record, else stable version. */
    public int resolveTargetVersion(long scriptId, Device device) {
        List<PublishRecord> records = publishRecordMapper.selectList(
                new QueryWrapper<PublishRecord>().eq("script_id", scriptId).orderByDesc("id"));
        for (PublishRecord r : records) {
            if (matches(device, r.targetType, r.targetValue)) return r.versionCode;
        }
        Script s = scriptMapper.selectById(scriptId);
        return s == null || s.stableVersionCode == null ? 0 : s.stableVersionCode;
    }

    private boolean matches(Device device, String targetType, String targetValue) {
        return GrayRule.matches(device.deviceSn, device.groupId, targetType, targetValue);
    }

    /** After device register: compute script updates the device should install. */
    public List<WsMessage> updatesForDevice(Device device, Map<Long, Integer> installed) {
        List<WsMessage> updates = new ArrayList<>();
        List<Script> scripts = scriptMapper.selectList(null);
        Set<Long> relevantScriptIds = new HashSet<>(installed.keySet());
        for (Script s : scripts) {
            if (s.stableVersionCode != null && s.stableVersionCode > 0) relevantScriptIds.add(s.id);
        }
        for (Long scriptId : relevantScriptIds) {
            int target = resolveTargetVersion(scriptId, device);
            if (target <= 0) continue;
            Integer has = installed.get(scriptId);
            if (has != null && has == target) continue;
            ScriptVersion version = versionMapper.selectOne(new QueryWrapper<ScriptVersion>()
                    .eq("script_id", scriptId).eq("version_code", target).last("LIMIT 1"));
            if (version != null) updates.add(updateMessage(scriptId, version));
        }
        return updates;
    }

    public List<PublishRecord> records(long scriptId) {
        return publishRecordMapper.selectList(
                new QueryWrapper<PublishRecord>().eq("script_id", scriptId).orderByDesc("id"));
    }
}
