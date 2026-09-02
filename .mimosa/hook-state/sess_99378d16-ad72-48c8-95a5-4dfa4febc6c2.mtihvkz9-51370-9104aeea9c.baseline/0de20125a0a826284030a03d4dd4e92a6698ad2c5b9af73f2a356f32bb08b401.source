package com.rpa.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.rpa.server.common.ApiException;
import com.rpa.server.common.JsonUtil;
import com.rpa.server.entity.Script;
import com.rpa.server.entity.Task;
import com.rpa.server.entity.TaskDevice;
import com.rpa.server.mapper.ScriptMapper;
import com.rpa.server.mapper.TaskDeviceMapper;
import com.rpa.server.mapper.TaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class TaskService {
    private final TaskMapper taskMapper;
    private final TaskDeviceMapper taskDeviceMapper;
    private final ScriptMapper scriptMapper;
    private final ScriptService scriptService;
    private final TaskSchedulerService schedulerService;

    public TaskService(TaskMapper taskMapper, TaskDeviceMapper taskDeviceMapper,
                       ScriptMapper scriptMapper, ScriptService scriptService,
                       TaskSchedulerService schedulerService) {
        this.taskMapper = taskMapper;
        this.taskDeviceMapper = taskDeviceMapper;
        this.scriptMapper = scriptMapper;
        this.scriptService = scriptService;
        this.schedulerService = schedulerService;
    }

    @Transactional
    public Task create(String name, long scriptId, Integer versionCode, String paramsJson,
                       String scheduleType, String cronExpr, int maxRetries, List<Long> deviceIds) {
        validate(name, scriptId, paramsJson, scheduleType, cronExpr);
        Task t = new Task();
        t.name = name;
        t.scriptId = scriptId;
        t.versionCode = versionCode;
        t.paramsJson = paramsJson;
        t.scheduleType = scheduleType;
        t.cronExpr = cronExpr;
        t.maxRetries = Math.max(0, maxRetries);
        t.status = 1;
        taskMapper.insert(t);
        setDevices(t.id, deviceIds);
        schedulerService.refresh(t.id);
        return t;
    }

    @Transactional
    public void update(long id, String name, Integer versionCode, String paramsJson,
                       String scheduleType, String cronExpr, Integer maxRetries, List<Long> deviceIds) {
        Task t = require(id);
        validate(name != null ? name : t.name, t.scriptId, paramsJson, scheduleType, cronExpr);
        Task upd = new Task();
        upd.id = id;
        if (name != null) upd.name = name;
        if (versionCode != null) upd.versionCode = versionCode;
        if (paramsJson != null) upd.paramsJson = paramsJson;
        if (scheduleType != null) {
            upd.scheduleType = scheduleType;
            upd.cronExpr = "CRON".equals(scheduleType) ? cronExpr : null;
        }
        if (maxRetries != null) upd.maxRetries = Math.max(0, maxRetries);
        taskMapper.updateById(upd);
        if (deviceIds != null) setDevices(id, deviceIds);
        schedulerService.refresh(id);
    }

    private void validate(String name, long scriptId, String paramsJson, String scheduleType, String cronExpr) {
        if (name == null || name.isBlank()) throw new ApiException("任务名不能为空");
        Script script = scriptMapper.selectById(scriptId);
        if (script == null) throw new ApiException(404, "脚本不存在");
        if (paramsJson != null && !paramsJson.isBlank()) {
            try {
                JsonNode node = JsonUtil.MAPPER.readTree(paramsJson);
                if (!node.isObject()) throw new IllegalArgumentException("not object");
            } catch (Exception e) {
                throw new ApiException("params 必须是合法的 JSON 对象");
            }
        }
        if (!"IMMEDIATE".equals(scheduleType) && !"CRON".equals(scheduleType)) {
            throw new ApiException("scheduleType 必须为 IMMEDIATE 或 CRON");
        }
        if ("CRON".equals(scheduleType) && (cronExpr == null || cronExpr.isBlank())) {
            throw new ApiException("CRON 任务必须填写 cron 表达式");
        }
    }

    @Transactional
    public void setDevices(long taskId, List<Long> deviceIds) {
        require(taskId);
        Set<Long> target = new HashSet<>(deviceIds == null ? List.of() : deviceIds);
        List<TaskDevice> current = taskDeviceMapper.selectList(
                new QueryWrapper<TaskDevice>().eq("task_id", taskId));
        Set<Long> currentIds = new HashSet<>();
        for (TaskDevice td : current) {
            currentIds.add(td.deviceId);
            if (!target.contains(td.deviceId)) {
                taskDeviceMapper.deleteById(td.id);
            }
        }
        for (Long deviceId : target) {
            if (!currentIds.contains(deviceId)) {
                TaskDevice td = new TaskDevice();
                td.taskId = taskId;
                td.deviceId = deviceId;
                td.status = "PENDING";
                td.retryCount = 0;
                td.successCount = 0;
                td.failCount = 0;
                taskDeviceMapper.insert(td);
            }
        }
    }

    public void delete(long id) {
        require(id);
        taskMapper.deleteById(id);
        taskDeviceMapper.delete(new QueryWrapper<TaskDevice>().eq("task_id", id));
        schedulerService.refresh(id);
    }

    public void setStatus(long id, int status) {
        require(id);
        Task upd = new Task();
        upd.id = id;
        upd.status = status;
        taskMapper.updateById(upd);
        schedulerService.refresh(id);
    }

    public List<Map<String, Object>> list() {
        List<Task> tasks = taskMapper.selectList(new QueryWrapper<Task>().orderByDesc("id"));
        Map<Long, String> scriptNames = new HashMap<>();
        scriptService.list().forEach(s -> scriptNames.put(s.id, s.name));
        return tasks.stream().map(t -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", t.id);
            m.put("name", t.name);
            m.put("scriptId", t.scriptId);
            m.put("scriptName", scriptNames.get(t.scriptId));
            m.put("versionCode", t.versionCode);
            m.put("paramsJson", t.paramsJson);
            m.put("scheduleType", t.scheduleType);
            m.put("cronExpr", t.cronExpr);
            m.put("maxRetries", t.maxRetries);
            m.put("status", t.status);
            m.put("createdAt", t.createdAt);
            m.put("deviceCount", taskDeviceMapper.selectCount(
                    new QueryWrapper<TaskDevice>().eq("task_id", t.id)));
            m.put("runningCount", taskDeviceMapper.selectCount(
                    new QueryWrapper<TaskDevice>().eq("task_id", t.id).eq("status", "RUNNING")));
            return m;
        }).toList();
    }

    public Task require(long id) {
        Task t = taskMapper.selectById(id);
        if (t == null) throw new ApiException(404, "任务不存在");
        return t;
    }
}
