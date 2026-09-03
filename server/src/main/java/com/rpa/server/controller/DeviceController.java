package com.rpa.server.controller;

import com.rpa.server.common.R;
import com.rpa.server.entity.Device;
import com.rpa.server.service.DeviceDebugService;
import com.rpa.server.service.DeviceService;
import com.rpa.server.service.TaskControlService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/devices")
public class DeviceController {
    private final DeviceService deviceService;
    private final TaskControlService taskControlService;
    private final DeviceDebugService deviceDebugService;

    public DeviceController(DeviceService deviceService, TaskControlService taskControlService,
                            DeviceDebugService deviceDebugService) {
        this.deviceService = deviceService;
        this.taskControlService = taskControlService;
        this.deviceDebugService = deviceDebugService;
    }

    @RequestMapping("/page")
    public R<Map<String, Object>> page(@RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) Long groupId,
                                       @RequestParam(required = false) Integer online,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "10") int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 200);
        return R.ok(deviceService.page(keyword, groupId, online, safePage, safeSize));
    }

    /** 不分页精简列表，供任务/分组/日志等选择器使用，避免分页上限截断。 */
    @GetMapping("/options")
    public R<java.util.List<Map<String, Object>>> options() {
        return R.ok(deviceService.options());
    }

    @PostMapping
    public R<Device> create(@RequestBody Map<String, Object> body) {
        String sn = (String) body.get("deviceSn");
        String name = (String) body.get("name");
        Long groupId = body.get("groupId") == null ? null : Long.valueOf(String.valueOf(body.get("groupId")));
        return R.ok(deviceService.create(sn, name, groupId));
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        Long groupId = body.get("groupId") == null ? null : Long.valueOf(String.valueOf(body.get("groupId")));
        Integer status = body.get("status") == null ? null : Integer.valueOf(String.valueOf(body.get("status")));
        deviceService.update(id, name, groupId, status);
        return R.ok();
    }

    @PostMapping("/{id}/reset-secret")
    public R<Map<String, String>> resetSecret(@PathVariable long id) {
        return R.ok(Map.of("secret", deviceService.resetSecret(id)));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable long id) {
        deviceService.delete(id);
        return R.ok();
    }

    @PostMapping("/{id}/command")
    public R<Void> command(@PathVariable long id, @RequestBody Map<String, Object> body) {
        long taskId;
        try {
            taskId = Long.parseLong(String.valueOf(body.get("taskId")));
        } catch (NumberFormatException e) {
            throw new com.rpa.server.common.ApiException("taskId 必须为整数");
        }
        String action = body.get("action") == null ? null : String.valueOf(body.get("action"));
        if (action == null) throw new com.rpa.server.common.ApiException("action 不能为空");
        taskControlService.controlDevice(taskId, id, action);
        return R.ok();
    }

    /** 触发设备 UI 树/截图调试上报（kind=dump|capture），结果经 STOMP /topic/device/{id}/debug 推送。 */
    @PostMapping("/{id}/debug/{kind}")
    public R<Void> debugTrigger(@PathVariable long id, @PathVariable String kind) {
        deviceDebugService.request(id, kind);
        return R.ok();
    }

    /** 最近一次调试结果（页面初次打开/MCP 轮询用）；从未上报过时 data 为 null。 */
    @GetMapping("/{id}/debug/latest")
    public R<Map<String, Object>> debugLatest(@PathVariable long id, @RequestParam String type) {
        return R.ok(deviceDebugService.latest(id, type));
    }
}
