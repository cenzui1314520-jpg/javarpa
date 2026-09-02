package com.rpa.server.controller;

import com.rpa.server.common.R;
import com.rpa.server.entity.DeviceGroup;
import com.rpa.server.service.DeviceGroupService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/groups")
public class DeviceGroupController {
    private final DeviceGroupService groupService;

    public DeviceGroupController(DeviceGroupService groupService) {
        this.groupService = groupService;
    }

    @GetMapping
    public R<List<Map<String, Object>>> list() {
        return R.ok(groupService.list());
    }

    @PostMapping
    public R<DeviceGroup> create(@RequestBody Map<String, String> body) {
        return R.ok(groupService.create(body.get("name"), body.get("remark")));
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable long id, @RequestBody Map<String, String> body) {
        groupService.update(id, body.get("name"), body.get("remark"));
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable long id) {
        groupService.delete(id);
        return R.ok();
    }

    @PostMapping("/{id}/members")
    public R<Void> setMembers(@PathVariable long id, @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Number> deviceIds = (List<Number>) body.get("deviceIds");
        groupService.setMembers(id, deviceIds == null
                ? List.of() : deviceIds.stream().map(Number::longValue).toList());
        return R.ok();
    }

    @GetMapping("/{id}/devices")
    public R<Object> devices(@PathVariable long id) {
        return R.ok(groupService.devicesOf(id));
    }
}
