package com.rpa.server.controller;

import com.rpa.server.common.R;
import com.rpa.server.service.StatsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/stats")
public class StatsController {
    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/summary")
    public R<Map<String, Object>> summary() {
        return R.ok(statsService.summary());
    }

    @GetMapping("/trend")
    public R<List<Map<String, Object>>> trend(@RequestParam(defaultValue = "7") int days) {
        if (days < 1) days = 7;
        if (days > 90) days = 90;
        return R.ok(statsService.trend(days));
    }

    @GetMapping("/by-task")
    public R<List<Map<String, Object>>> byTask(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        return R.ok(statsService.byTask(start, end));
    }
}
