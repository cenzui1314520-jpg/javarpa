package com.rpa.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rpa.server.common.ApiException;
import com.rpa.server.entity.Device;
import com.rpa.server.entity.Script;
import com.rpa.server.entity.ScriptVersion;
import com.rpa.server.entity.Task;
import com.rpa.server.entity.TaskDevice;
import com.rpa.server.entity.TaskExecution;
import com.rpa.server.mapper.DeviceMapper;
import com.rpa.server.mapper.ScriptMapper;
import com.rpa.server.mapper.ScriptVersionMapper;
import com.rpa.server.mapper.TaskDeviceMapper;
import com.rpa.server.mapper.TaskExecutionMapper;
import com.rpa.server.mapper.TaskMapper;
import com.rpa.server.ws.AdminStompService;
import com.rpa.server.ws.DeviceSessionManager;
import com.rpa.server.ws.RedisQueueService;
import com.rpa.server.ws.WsMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Routes task commands (start/pause/stop/restart) to devices and handles results/retries. */
@Service
public class TaskControlService {
    private static final Logger log = LoggerFactory.getLogger(TaskControlService.class);

    private final TaskMapper taskMapper;
    private final TaskDeviceMapper taskDeviceMapper;
    private final TaskExecutionMapper executionMapper;
    private final DeviceMapper deviceMapper;
    private final ScriptMapper scriptMapper;
    private final ScriptVersionMapper versionMapper;
    private final DeviceSessionManager sessionManager;
    private final RedisQueueService redisQueue;
    private final AdminStompService stomp;

    public TaskControlService(TaskMapper taskMapper, TaskDeviceMapper taskDeviceMapper,
                              TaskExecutionMapper executionMapper, DeviceMapper deviceMapper,
                              ScriptMapper scriptMapper, ScriptVersionMapper versionMapper,
                              DeviceSessionManager sessionManager, RedisQueueService redisQueue,
                              AdminStompService stomp) {
        this.taskMapper = taskMapper;
        this.taskDeviceMapper = taskDeviceMapper;
        this.executionMapper = executionMapper;
        this.deviceMapper = deviceMapper;
        this.scriptMapper = scriptMapper;
        this.versionMapper = versionMapper;
        this.sessionManager = sessionManager;
        this.redisQueue = redisQueue;
        this.stomp = stomp;
    }

    public void controlTask(long taskId, String action) {
        Task task = requireTask(taskId);
        List<TaskDevice> targets = taskDeviceMapper.selectList(
                new QueryWrapper<TaskDevice>().eq("task_id", taskId));
        for (TaskDevice td : targets) {
            controlOne(task, td, action);
        }
    }

    public void controlDevice(long taskId, long deviceId, String action) {
        Task task = requireTask(taskId);
        TaskDevice td = taskDeviceMapper.selectOne(new QueryWrapper<TaskDevice>()
                .eq("task_id", taskId).eq("device_id", deviceId).last("LIMIT 1"));
        if (td == null) throw new ApiException(404, "该设备不在此任务中");
        controlOne(task, td, action);
    }

    private void controlOne(Task task, TaskDevice td, String action) {
        switch (action) {
            case "start" -> {
                td.status = "PENDING";
                td.retryCount = 0;
                taskDeviceMapper.updateById(td);
                dispatchStart(task, td);
            }
            case "pause" -> sendOrQueue(td.deviceId, WsMessage.of("CMD_PAUSE",
                    Map.of("taskId", task.id)));
            case "stop" -> sendOrQueue(td.deviceId, WsMessage.of("CMD_STOP",
                    Map.of("taskId", task.id)));
            case "restart" -> {
                td.status = "PENDING";
                taskDeviceMapper.updateById(td);
                sendOrQueue(td.deviceId, WsMessage.of("CMD_RESTART",
                        Map.of("taskId", task.id)));
            }
            default -> throw new ApiException("不支持的操作: " + action);
        }
        pushTaskDeviceStatus(td);
    }

    private void dispatchStart(Task task, TaskDevice td) {
        Map<String, Object> data = buildStartPayload(task, td);
        if (data == null) return;
        sendOrQueue(td.deviceId, WsMessage.of("CMD_START", data));
    }

    private Map<String, Object> buildStartPayload(Task task, TaskDevice td) {
        Script script = scriptMapper.selectById(task.scriptId);
        if (script == null) {
            markFailed(td, "脚本不存在");
            return null;
        }
        int versionCode = task.versionCode != null ? task.versionCode
                : (script.stableVersionCode != null ? script.stableVersionCode : 0);
        ScriptVersion version = versionMapper.selectOne(new QueryWrapper<ScriptVersion>()
                .eq("script_id", script.id).eq("version_code", versionCode).last("LIMIT 1"));
        if (version == null) {
            markFailed(td, "脚本 v" + versionCode + " 未上传");
            return null;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("taskId", task.id);
        data.put("scriptId", script.id);
        data.put("versionCode", versionCode);
        data.put("url", version.filePath);
        data.put("md5", version.fileMd5);
        data.put("params", task.paramsJson == null ? Map.of() : parseParams(task.paramsJson));
        return data;
    }

    private Map<String, Object> parseParams(String json) {
        try {
            return com.rpa.server.common.JsonUtil.toMap(json);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private void markFailed(TaskDevice td, String reason) {
        td.status = "FAILED";
        taskDeviceMapper.updateById(td);
        TaskExecution exec = new TaskExecution();
        exec.taskId = td.taskId;
        exec.deviceId = td.deviceId;
        exec.status = "FAILED";
        exec.successCount = 0;
        exec.failCount = 0;
        exec.errorMsg = reason;
        executionMapper.insert(exec);
        pushTaskDeviceStatus(td);
    }

    private void sendOrQueue(long deviceId, WsMessage msg) {
        if (!sessionManager.send(String.valueOf(deviceId), msg)) {
            Device device = deviceMapper.selectById(deviceId);
            if (device != null && device.status != null && device.status == 1) {
                redisQueue.pushPending(deviceId, msg);
            }
        }
    }

    private static final List<String> TERMINAL_STATUSES = List.of("SUCCESS", "FAILED", "STOPPED");

    public void updateProgress(long taskId, long deviceId, int successCount, int failCount) {
        // 条件更新：终态行不再被心跳覆盖回 RUNNING，避免读-改-写并发丢失更新
        taskDeviceMapper.update(null, new UpdateWrapper<TaskDevice>()
                .set("success_count", successCount)
                .set("fail_count", failCount)
                .set("last_run_at", LocalDateTime.now())
                .set("status", "RUNNING")
                .eq("task_id", taskId).eq("device_id", deviceId)
                .notIn("status", TERMINAL_STATUSES));
    }

    /** Handle RESULT reported by device, including failure retry. */
    public void handleResult(long deviceId, Map<String, Object> data) {
        long taskId = toLong(data.get("taskId"));
        String status = str(data.get("status"));
        int successCount = toInt(data.get("successCount"));
        int failCount = toInt(data.get("failCount"));

        TaskDevice td = findTd(taskId, deviceId);
        if (td == null) return;
        boolean terminal = TERMINAL_STATUSES.contains(status);
        int updated = taskDeviceMapper.update(null, new UpdateWrapper<TaskDevice>()
                .set("success_count", successCount)
                .set("fail_count", failCount)
                .set("last_run_at", LocalDateTime.now())
                .set("status", terminal ? status : "RUNNING")
                .eq("task_id", taskId).eq("device_id", deviceId)
                .notIn("status", TERMINAL_STATUSES));
        if (updated == 0) return; // 行已处于终态，忽略迟到/重复结果
        td.successCount = successCount;
        td.failCount = failCount;
        td.status = terminal ? status : "RUNNING";

        if (terminal) {
            TaskExecution exec = new TaskExecution();
            exec.taskId = taskId;
            exec.deviceId = deviceId;
            exec.status = status;
            exec.successCount = successCount;
            exec.failCount = failCount;
            exec.errorMsg = str(data.get("errorMsg"));
            exec.durationMs = parseDuration(data.get("duration"));
            executionMapper.insert(exec);
        }
        pushTaskDeviceStatus(td);

        if ("FAILED".equals(status)) {
            Task task = taskMapper.selectById(taskId);
            if (task != null && task.maxRetries != null) {
                int claimed = taskDeviceMapper.update(null, new UpdateWrapper<TaskDevice>()
                        .setSql("retry_count = retry_count + 1")
                        .set("status", "PENDING")
                        .eq("task_id", taskId).eq("device_id", deviceId)
                        .eq("status", "FAILED")
                        .apply("retry_count < {0}", task.maxRetries));
                if (claimed > 0) {
                    td.retryCount = (td.retryCount == null ? 0 : td.retryCount) + 1;
                    td.status = "PENDING";
                    log.info("task {} device {} failed, retry #{}", taskId, deviceId, td.retryCount);
                    dispatchStart(task, td);
                    pushTaskDeviceStatus(td);
                }
            }
        }
    }

    private static Long parseDuration(Object v) {
        if (v == null) return null;
        try {
            return (long) (Double.parseDouble(String.valueOf(v)) * 1000);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void pushTaskDeviceStatus(TaskDevice td) {
        Map<String, Object> m = new HashMap<>();
        m.put("taskId", td.taskId);
        m.put("deviceId", td.deviceId);
        m.put("status", td.status);
        m.put("successCount", td.successCount);
        m.put("failCount", td.failCount);
        m.put("retryCount", td.retryCount);
        stomp.pushTaskDeviceStatus(td.taskId, m);
    }

    private TaskDevice findTd(long taskId, long deviceId) {
        return taskDeviceMapper.selectOne(new QueryWrapper<TaskDevice>()
                .eq("task_id", taskId).eq("device_id", deviceId).last("LIMIT 1"));
    }

    private Task requireTask(long id) {
        Task t = taskMapper.selectById(id);
        if (t == null) throw new ApiException(404, "任务不存在");
        return t;
    }

    private static long toLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return -1; // 非法 id 查不到对应行，自然忽略
        }
    }

    private static int toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        try {
            return v == null ? 0 : Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
