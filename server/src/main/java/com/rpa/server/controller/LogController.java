package com.rpa.server.controller;

import com.rpa.server.common.R;
import com.rpa.server.service.DeviceLogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/logs")
public class LogController {
    private final DeviceLogService deviceLogService;

    public LogController(DeviceLogService deviceLogService) {
        this.deviceLogService = deviceLogService;
    }

    @GetMapping
    public R<Map<String, Object>> page(@RequestParam(required = false) Long deviceId,
                                       @RequestParam(required = false) Long taskId,
                                       @RequestParam(required = false) String level,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 500); // 日志页默认 50，上限放宽到 500
        return R.ok(deviceLogService.page(deviceId, taskId, level, safePage, safeSize));
    }
}
