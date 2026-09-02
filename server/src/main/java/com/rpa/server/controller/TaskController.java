package com.rpa.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rpa.server.common.R;
import com.rpa.server.entity.Device;
import com.rpa.server.entity.Task;
import com.rpa.server.entity.TaskDevice;
import com.rpa.server.entity.TaskExecution;
import com.rpa.server.mapper.DeviceMapper;
import com.rpa.server.mapper.TaskDeviceMapper;
import com.rpa.server.mapper.TaskExecutionMapper;
import com.rpa.server.service.TaskControlService;
import com.rpa.server.service.TaskService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/tasks")
public class TaskController {
    private final TaskService taskService;
    private final TaskControlService taskControlService;
    private final TaskDeviceMapper taskDeviceMapper;
    private final TaskExecutionMapper executionMapper;
    private final DeviceMapper deviceMapper;

    public TaskController(TaskService taskService, TaskControlService taskControlService,
                          TaskDeviceMapper taskDeviceMapper, TaskExecutionMapper executionMapper,
                          DeviceMapper deviceMapper) {
        this.taskService = taskService;
        this.taskControlService = taskControlService;
        this.taskDeviceMapper = taskDeviceMapper;
        this.executionMapper = executionMapper;
        this.deviceMapper = deviceMapper;
    }

    @GetMapping
    public R<List<Map<String, Object>>> list() {
        return R.ok(taskService.list());
    }

    @PostMapping
    public R<Task> create(@RequestBody TaskCreateReq req) {
        // scriptId 缺失直接 400，避免 Long 拆箱 NPE 变成 500
        if (req.scriptId() == null) throw new com.rpa.server.common.ApiException("scriptId 不能为空");
        return R.ok(taskService.create(req.name(), req.scriptId(), req.versionCode(),
                req.paramsJson(), req.scheduleType(), req.cronExpr(),
                req.maxRetries() == null ? 0 : req.maxRetries(), req.deviceIds()));
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable long id, @RequestBody TaskCreateReq req) {
        taskService.update(id, req.name(), req.versionCode(), req.paramsJson(),
                req.scheduleType(), req.cronExpr(), req.maxRetries(), req.deviceIds());
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable long id) {
        taskService.delete(id);
        return R.ok();
    }

    @PostMapping("/{id}/actions")
    public R<Void> actions(@PathVariable long id, @RequestBody Map<String, String> body) {
        String action = body.get("action");
        if (action == null) throw new com.rpa.server.common.ApiException("action 不能为空");
        switch (action) {
            case "start", "pause", "stop", "restart" -> taskControlService.controlTask(id, action);
            case "enable" -> taskService.setStatus(id, 1);
            case "disable" -> taskService.setStatus(id, 0);
            default -> throw new com.rpa.server.common.ApiException("不支持的操作: " + action);
        }
        return R.ok();
    }

    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable long id) {
        Task task = taskService.require(id);
        List<TaskDevice> taskDevices = taskDeviceMapper.selectList(
                new QueryWrapper<TaskDevice>().eq("task_id", id));
        List<Long> deviceIds = taskDevices.stream().map(td -> td.deviceId).toList();
        Map<Long, Device> devices = new HashMap<>();
        if (!deviceIds.isEmpty()) {
            deviceMapper.selectBatchIds(deviceIds).forEach(d -> devices.put(d.id, d));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("task", task);
        result.put("taskDevices", taskDevices.stream().map(td -> {
            Map<String, Object> m = new HashMap<>();
            m.put("deviceId", td.deviceId);
            Device d = devices.get(td.deviceId);
            m.put("deviceSn", d == null ? "?" : d.deviceSn);
            m.put("deviceName", d == null ? "?" : d.name);
            m.put("online", d == null ? 0 : d.online);
            m.put("status", td.status);
            m.put("retryCount", td.retryCount);
            m.put("successCount", td.successCount);
            m.put("failCount", td.failCount);
            m.put("lastRunAt", td.lastRunAt);
            return m;
        }).toList());
        result.put("executions", executionMapper.selectPage(
                Page.of(1, 50),
                new QueryWrapper<TaskExecution>().eq("task_id", id).orderByDesc("id")).getRecords());
        return R.ok(result);
    }

    public record TaskCreateReq(String name, Long scriptId, Integer versionCode, String paramsJson,
                                String scheduleType, String cronExpr, Integer maxRetries,
                                List<Long> deviceIds) {}
}
