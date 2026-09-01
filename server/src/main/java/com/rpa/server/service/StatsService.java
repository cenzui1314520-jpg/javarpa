package com.rpa.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rpa.server.entity.Device;
import com.rpa.server.entity.Script;
import com.rpa.server.entity.StatsDaily;
import com.rpa.server.entity.Task;
import com.rpa.server.entity.TaskDevice;
import com.rpa.server.entity.TaskExecution;
import com.rpa.server.mapper.DeviceMapper;
import com.rpa.server.mapper.ScriptMapper;
import com.rpa.server.mapper.StatsDailyMapper;
import com.rpa.server.mapper.TaskMapper;
import com.rpa.server.mapper.TaskExecutionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class StatsService {
    private static final Logger log = LoggerFactory.getLogger(StatsService.class);

    private final StatsDailyMapper statsMapper;
    private final TaskExecutionMapper executionMapper;
    private final TaskMapper taskMapper;
    private final ScriptMapper scriptMapper;
    private final DeviceMapper deviceMapper;

    public StatsService(StatsDailyMapper statsMapper, TaskExecutionMapper executionMapper,
                        TaskMapper taskMapper, ScriptMapper scriptMapper, DeviceMapper deviceMapper) {
        this.statsMapper = statsMapper;
        this.executionMapper = executionMapper;
        this.taskMapper = taskMapper;
        this.scriptMapper = scriptMapper;
        this.deviceMapper = deviceMapper;
    }

    @Scheduled(cron = "0 5 1 * * ?")
    @Transactional
    public void aggregateYesterday() {
        recomputeDaily(LocalDate.now().minusDays(1));
    }

    /** Rebuild stats_daily of a given date from task_execution. */
    @Transactional
    public void recomputeDaily(LocalDate date) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();
        statsMapper.delete(new QueryWrapper<StatsDaily>().eq("stat_date", date));

        Map<Long, Task> tasks = new HashMap<>();
        taskMapper.selectList(null).forEach(t -> tasks.put(t.id, t));
        List<TaskExecution> executions = executionMapper.selectList(new QueryWrapper<TaskExecution>()
                .ge("created_at", start).lt("created_at", end));

        Map<Long, int[]> byTask = new HashMap<>(); // [total, success, fail, successCnt, failCnt]
        for (TaskExecution e : executions) {
            int[] arr = byTask.computeIfAbsent(e.taskId, k -> new int[5]);
            arr[0]++;
            if ("SUCCESS".equals(e.status)) arr[1]++;
            if ("FAILED".equals(e.status)) arr[2]++;
            arr[3] += e.successCount == null ? 0 : e.successCount;
            arr[4] += e.failCount == null ? 0 : e.failCount;
        }
        for (Map.Entry<Long, int[]> entry : byTask.entrySet()) {
            Task task = tasks.get(entry.getKey());
            if (task == null) continue;
            StatsDaily s = new StatsDaily();
            s.statDate = date;
            s.taskId = entry.getKey();
            s.scriptId = task.scriptId;
            int[] a = entry.getValue();
            s.totalExec = a[0];
            s.successExec = a[1];
            s.failExec = a[2];
            s.successCnt = a[3];
            s.failCnt = a[4];
            statsMapper.insert(s);
        }
        log.info("stats of {} recomputed: {} tasks", date, byTask.size());
    }

    public Map<String, Object> summary() {
        Map<String, Object> m = new HashMap<>();
        m.put("deviceTotal", deviceMapper.selectCount(null));
        m.put("deviceOnline", deviceMapper.selectCount(
                new QueryWrapper<Device>().eq("online", 1)));
        m.put("taskTotal", taskMapper.selectCount(null));
        m.put("taskRunning", executionMapper.selectCount(
                new QueryWrapper<TaskExecution>().eq("status", "RUNNING")));

        LocalDate today = LocalDate.now();
        LocalDateTime start = today.atStartOfDay();
        List<TaskExecution> executions = executionMapper.selectList(
                new QueryWrapper<TaskExecution>().ge("created_at", start));
        long total = executions.size();
        long success = executions.stream().filter(e -> "SUCCESS".equals(e.status)).count();
        long failed = executions.stream().filter(e -> "FAILED".equals(e.status)).count();
        long successCnt = executions.stream().mapToLong(e -> e.successCount == null ? 0 : e.successCount).sum();
        long failCnt = executions.stream().mapToLong(e -> e.failCount == null ? 0 : e.failCount).sum();
        m.put("todayExecTotal", total);
        m.put("todaySuccess", success);
        m.put("todayFailed", failed);
        m.put("todaySuccessRate", total == 0 ? null : Math.round(success * 1000.0 / total) / 10.0);
        m.put("todayOpSuccess", successCnt);
        m.put("todayOpFail", failCnt);
        return m;
    }

    public List<Map<String, Object>> trend(int days) {
        List<Map<String, Object>> result = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = days - 1; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            Map<String, Object> row = new HashMap<>();
            row.put("date", date.toString());
            if (date.equals(today)) {
                List<TaskExecution> executions = executionMapper.selectList(
                        new QueryWrapper<TaskExecution>().ge("created_at", date.atStartOfDay()));
                row.put("total", executions.size());
                row.put("success", executions.stream().filter(e -> "SUCCESS".equals(e.status)).count());
                row.put("failed", executions.stream().filter(e -> "FAILED".equals(e.status)).count());
            } else {
                List<StatsDaily> stats = statsMapper.selectList(
                        new QueryWrapper<StatsDaily>().eq("stat_date", date));
                row.put("total", stats.stream().mapToInt(s -> s.totalExec).sum());
                row.put("success", stats.stream().mapToInt(s -> s.successExec).sum());
                row.put("failed", stats.stream().mapToInt(s -> s.failExec).sum());
            }
            result.add(row);
        }
        return result;
    }

    public List<Map<String, Object>> byTask(LocalDate start, LocalDate end) {
        Map<Long, Task> tasks = new HashMap<>();
        taskMapper.selectList(null).forEach(t -> tasks.put(t.id, t));
        Map<Long, Script> scripts = new HashMap<>();
        scriptMapper.selectList(null).forEach(s -> scripts.put(s.id, s));
        Map<Long, String> scriptNames = new HashMap<>();
        scripts.forEach((id, s) -> scriptNames.put(id, s.name));

        List<TaskExecution> executions = executionMapper.selectList(new QueryWrapper<TaskExecution>()
                .ge("created_at", start.atStartOfDay())
                .lt("created_at", end.plusDays(1).atStartOfDay()));
        Map<Long, int[]> byTask = new HashMap<>();
        for (TaskExecution e : executions) {
            int[] arr = byTask.computeIfAbsent(e.taskId, k -> new int[5]);
            arr[0]++;
            if ("SUCCESS".equals(e.status)) arr[1]++;
            if ("FAILED".equals(e.status)) arr[2]++;
            arr[3] += e.successCount == null ? 0 : e.successCount;
            arr[4] += e.failCount == null ? 0 : e.failCount;
        }
        List<Map<String, Object>> result = new ArrayList<>();
        byTask.forEach((taskId, a) -> {
            Task task = tasks.get(taskId);
            if (task == null) return;
            Map<String, Object> m = new HashMap<>();
            m.put("taskId", taskId);
            m.put("taskName", task.name);
            m.put("scriptName", scriptNames.get(task.scriptId));
            m.put("total", a[0]);
            m.put("success", a[1]);
            m.put("failed", a[2]);
            m.put("successRate", a[0] == 0 ? null : Math.round(a[1] * 1000.0 / a[0]) / 10.0);
            m.put("opSuccess", a[3]);
            m.put("opFail", a[4]);
            result.add(m);
        });
        result.sort((x, y) -> Long.compare((long) y.get("total"), (long) x.get("total")));
        return result;
    }
}
