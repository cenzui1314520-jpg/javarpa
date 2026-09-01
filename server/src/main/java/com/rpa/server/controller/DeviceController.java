package com.rpa.server.controller;

import com.rpa.server.common.R;
import com.rpa.server.entity.Device;
import com.rpa.server.service.DeviceService;
import com.rpa.server.service.TaskControlService;
import org.springframework.web.bind.annotation.DeleteMapping;
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

    public DeviceController(DeviceService deviceService, TaskControlService taskControlService) {
        this.deviceService = deviceService;
        this.taskControlService = taskControlService;
    }

    @RequestMapping("/page")
    public R<Map<String, Object>> page(@RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) Long groupId,
                                       @RequestParam(required = false) Integer online,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "10") int size) {
        return R.ok(deviceService.page(keyword, groupId, online, page, size));
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
        long taskId = Long.parseLong(String.valueOf(body.get("taskId")));
        String action = String.valueOf(body.get("action"));
        taskControlService.controlDevice(taskId, id, action);
        return R.ok();
    }
}
