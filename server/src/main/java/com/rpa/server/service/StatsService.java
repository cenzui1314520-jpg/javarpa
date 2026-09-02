package com.rpa.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rpa.server.entity.Device;
import com.rpa.server.entity.Script;
import com.rpa.server.entity.StatsDaily;
import com.rpa.server.entity.Task;
import com.rpa.server.entity.TaskDevice;
import com.rpa.server.entity.TaskExecution;
import com.rpa.server.entity.TaskDevice;
import com.rpa.server.mapper.DeviceMapper;
import com.rpa.server.mapper.ScriptMapper;
import com.rpa.server.mapper.StatsDailyMapper;
import com.rpa.server.mapper.TaskDeviceMapper;
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
    private final TaskDeviceMapper taskDeviceMapper;

    public StatsService(StatsDailyMapper statsMapper, TaskExecutionMapper executionMapper,
                        TaskMapper taskMapper, ScriptMapper scriptMapper, DeviceMapper deviceMapper,
                        TaskDeviceMapper taskDeviceMapper) {
        this.statsMapper = statsMapper;
        this.executionMapper = executionMapper;
        this.taskMapper = taskMapper;
        this.scriptMapper = scriptMapper;
        this.deviceMapper = deviceMapper;
        this.taskDeviceMapper = taskDeviceMapper;
    }

    @Scheduled(cron = "0 5 1 * * ?")
    @Transactional
    public void aggregateYesterday() {
        recomputeDaily(LocalDate.now().minusDays(1));
    }

    /** 离线设备的迟到 RESULT 在 01:05 聚合后才会落库，每小时补偿重算昨日保证口径一致。 */
    @Scheduled(cron = "0 40 * * * ?")
    @Transactional
    public void recomputeYesterdayHourly() {
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
        // SQL 聚合替代全量加载，避免大表拖垮 Dashboard
        List<Map<String, Object>> rows = executionMapper.selectMaps(
                new QueryWrapper<TaskExecution>()
                        .select("task_id",
                                "COUNT(*) AS total",
                                "SUM(status = 'SUCCESS') AS success",
                                "SUM(status = 'FAILED') AS failed",
                                "COALESCE(SUM(success_count), 0) AS successCnt",
                                "COALESCE(SUM(fail_count), 0) AS failCnt")
                        .ge("created_at", start).lt("created_at", end)
                        .groupBy("task_id"));
        for (Map<String, Object> row : rows) {
            long taskId = ((Number) row.get("task_id")).longValue();
            Task task = tasks.get(taskId);
            if (task == null) continue;
            StatsDaily s = new StatsDaily();
            s.statDate = date;
            s.taskId = taskId;
            s.scriptId = task.scriptId;
            s.totalExec = intValue(row.get("total"));
            s.successExec = intValue(row.get("success"));
            s.failExec = intValue(row.get("failed"));
            s.successCnt = intValue(row.get("successCnt"));
            s.failCnt = intValue(row.get("failCnt"));
            statsMapper.insert(s);
        }
        log.info("stats of {} recomputed: {} tasks", date, rows.size());
    }

    private static int intValue(Object v) {
        return v == null ? 0 : ((Number) v).intValue();
    }

    public Map<String, Object> summary() {
        Map<String, Object> m = new HashMap<>();
        m.put("deviceTotal", deviceMapper.selectCount(null));
        m.put("deviceOnline", deviceMapper.selectCount(
                new QueryWrapper<Device>().eq("online", 1)));
        m.put("taskTotal", taskMapper.selectCount(null));
        // task_execution 只在终态落库，进行中任务数需查 task_device
        m.put("taskRunning", taskDeviceMapper.selectCount(
                new QueryWrapper<TaskDevice>().eq("status", "RUNNING")));

        LocalDate today = LocalDate.now();
        Map<String, Object> agg = aggregateSince(today.atStartOfDay());
        m.put("todayExecTotal", agg.get("total"));
        m.put("todaySuccess", agg.get("success"));
        m.put("todayFailed", agg.get("failed"));
        long total = ((Number) agg.get("total")).longValue();
        long success = ((Number) agg.get("success")).longValue();
        m.put("todaySuccessRate", total == 0 ? null : Math.round(success * 1000.0 / total) / 10.0);
        m.put("todayOpSuccess", agg.get("successCnt"));
        m.put("todayOpFail", agg.get("failCnt"));
        return m;
    }

    /** SQL 聚合统计某时刻起的执行结果，避免全量拉取 task_execution。 */
    private Map<String, Object> aggregateSince(LocalDateTime start) {
        List<Map<String, Object>> rows = executionMapper.selectMaps(
                new QueryWrapper<TaskExecution>()
                        .select("COUNT(*) AS total",
                                "SUM(status = 'SUCCESS') AS success",
                                "SUM(status = 'FAILED') AS failed",
                                "COALESCE(SUM(success_count), 0) AS successCnt",
                                "COALESCE(SUM(fail_count), 0) AS failCnt")
                        .ge("created_at", start));
        Map<String, Object> r = rows.isEmpty() ? Map.of() : rows.get(0);
        Map<String, Object> result = new HashMap<>();
        result.put("total", longValue(r.get("total")));
        result.put("success", longValue(r.get("success")));
        result.put("failed", longValue(r.get("failed")));
        result.put("successCnt", longValue(r.get("successCnt")));
        result.put("failCnt", longValue(r.get("failCnt")));
        return result;
    }

    private static long longValue(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
    }

    public List<Map<String, Object>> trend(int days) {
        List<Map<String, Object>> result = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = days - 1; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            Map<String, Object> row = new HashMap<>();
            row.put("date", date.toString());
            if (date.equals(today)) {
                Map<String, Object> agg = aggregateSince(date.atStartOfDay());
                row.put("total", agg.get("total"));
                row.put("success", agg.get("success"));
                row.put("failed", agg.get("failed"));
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

        List<Map<String, Object>> rows = executionMapper.selectMaps(
                new QueryWrapper<TaskExecution>()
                        .select("task_id",
                                "COUNT(*) AS total",
                                "SUM(status = 'SUCCESS') AS success",
                                "SUM(status = 'FAILED') AS failed",
                                "COALESCE(SUM(success_count), 0) AS successCnt",
                                "COALESCE(SUM(fail_count), 0) AS failCnt")
                        .ge("created_at", start.atStartOfDay())
                        .lt("created_at", end.plusDays(1).atStartOfDay())
                        .groupBy("task_id"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            long taskId = ((Number) row.get("task_id")).longValue();
            Task task = tasks.get(taskId);
            if (task == null) continue;
            long total = longValue(row.get("total"));
            long success = longValue(row.get("success"));
            Map<String, Object> m = new HashMap<>();
            m.put("taskId", taskId);
            m.put("taskName", task.name);
            m.put("scriptName", scriptNames.get(task.scriptId));
            m.put("total", total);
            m.put("success", success);
            m.put("failed", longValue(row.get("failed")));
            m.put("successRate", total == 0 ? null : Math.round(success * 1000.0 / total) / 10.0);
            m.put("opSuccess", longValue(row.get("successCnt")));
            m.put("opFail", longValue(row.get("failCnt")));
            result.add(m);
        }
        result.sort((x, y) -> Long.compare((long) y.get("total"), (long) x.get("total")));
        return result;
    }
}
