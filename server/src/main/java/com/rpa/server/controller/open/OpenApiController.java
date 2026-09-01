package com.rpa.server.controller.open;

import com.rpa.server.common.R;
import com.rpa.server.controller.TaskController;
import com.rpa.server.entity.Script;
import com.rpa.server.entity.ScriptVersion;
import com.rpa.server.entity.Task;
import com.rpa.server.mapper.DeviceMapper;
import com.rpa.server.mapper.TaskMapper;
import com.rpa.server.service.PublishService;
import com.rpa.server.service.ScriptService;
import com.rpa.server.service.StatsService;
import com.rpa.server.service.TaskControlService;
import com.rpa.server.service.TaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/** Open API for external systems, protected by X-API-Token. */
@RestController
@RequestMapping("/open/v1")
public class OpenApiController {
    private final DeviceMapper deviceMapper;
    private final TaskService taskService;
    private final TaskControlService taskControlService;
    private final ScriptService scriptService;
    private final PublishService publishService;
    private final StatsService statsService;

    public OpenApiController(DeviceMapper deviceMapper, TaskService taskService,
                             TaskControlService taskControlService, ScriptService scriptService,
                             PublishService publishService, StatsService statsService) {
        this.deviceMapper = deviceMapper;
        this.taskService = taskService;
        this.taskControlService = taskControlService;
        this.scriptService = scriptService;
        this.publishService = publishService;
        this.statsService = statsService;
    }

    @GetMapping("/devices")
    public R<List<Map<String, Object>>> devices() {
        return R.ok(deviceMapper.selectList(null).stream().<Map<String, Object>>map(d -> Map.of(
                "id", (Object) d.id, "deviceSn", d.deviceSn, "name", d.name == null ? "" : d.name,
                "online", d.online, "status", d.status, "lastActiveAt",
                d.lastActiveAt == null ? "" : d.lastActiveAt.toString())).toList());
    }

    @PostMapping("/tasks")
    public R<Task> createTask(@RequestBody TaskController.TaskCreateReq req) {
        return R.ok(taskService.create(req.name(), req.scriptId(), req.versionCode(),
                req.paramsJson(), req.scheduleType(), req.cronExpr(),
                req.maxRetries() == null ? 0 : req.maxRetries(), req.deviceIds()));
    }

    @PostMapping("/tasks/{id}/actions")
    public R<Void> taskAction(@PathVariable long id, @RequestBody Map<String, String> body) {
        String action = body.get("action");
        if ("start".equals(action) || "pause".equals(action) || "stop".equals(action)
                || "restart".equals(action)) {
            taskControlService.controlTask(id, action);
        } else if ("enable".equals(action) || "disable".equals(action)) {
            taskService.setStatus(id, "enable".equals(action) ? 1 : 0);
        } else {
            throw new com.rpa.server.common.ApiException("不支持的操作: " + action);
        }
        return R.ok();
    }

    @GetMapping("/tasks")
    public R<List<Map<String, Object>>> tasks() {
        return R.ok(taskService.list());
    }

    @PostMapping("/scripts")
    public R<Script> createScript(@RequestBody Map<String, String> body) {
        return R.ok(scriptService.create(body.get("name"), body.get("pkgName"), body.get("description")));
    }

    @PostMapping("/scripts/{id}/versions")
    public R<ScriptVersion> uploadVersion(@PathVariable long id,
                                          @RequestParam("file") MultipartFile file,
                                          @RequestParam("versionCode") int versionCode,
                                          @RequestParam(value = "versionName", required = false) String versionName,
                                          @RequestParam(value = "changelog", required = false) String changelog) {
        return R.ok(scriptService.uploadVersion(id, file, versionCode, versionName, changelog, "openapi"));
    }

    @PostMapping("/scripts/{id}/publish")
    public R<Void> publish(@PathVariable long id, @RequestBody Map<String, Object> body) {
        publishService.publish(id, Integer.parseInt(String.valueOf(body.get("versionCode"))),
                String.valueOf(body.get("targetType")),
                body.get("targetValue") == null ? null : String.valueOf(body.get("targetValue")),
                "openapi");
        return R.ok();
    }

    @GetMapping("/stats/summary")
    public R<Map<String, Object>> statsSummary() {
        return R.ok(statsService.summary());
    }
}
