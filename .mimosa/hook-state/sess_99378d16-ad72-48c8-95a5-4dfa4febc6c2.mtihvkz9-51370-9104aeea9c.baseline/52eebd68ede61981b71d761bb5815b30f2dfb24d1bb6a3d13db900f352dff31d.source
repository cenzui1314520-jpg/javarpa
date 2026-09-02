package com.rpa.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rpa.server.entity.Task;
import com.rpa.server.mapper.TaskMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/** Dynamic CRON scheduling for tasks. */
@Service
public class TaskSchedulerService {
    private static final Logger log = LoggerFactory.getLogger(TaskSchedulerService.class);

    private final TaskMapper taskMapper;
    private final TaskControlService taskControlService;

    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private final Map<Long, ScheduledFuture<?>> jobs = new ConcurrentHashMap<>();

    public TaskSchedulerService(TaskMapper taskMapper, @Lazy TaskControlService taskControlService) {
        this.taskMapper = taskMapper;
        this.taskControlService = taskControlService;
    }

    @PostConstruct
    public void init() {
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("task-cron-");
        scheduler.initialize();
        reloadAll();
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
    }

    /** Reload schedule state of one task (create/update/delete all call this). */
    public synchronized void refresh(long taskId) {
        cancel(taskId);
        Task task = taskMapper.selectById(taskId);
        if (task == null) return;
        if (task.status != null && task.status == 1 && "CRON".equals(task.scheduleType)
                && task.cronExpr != null && !task.cronExpr.isBlank()) {
            schedule(task);
        }
    }

    private void schedule(Task task) {
        try {
            CronTrigger trigger = new CronTrigger(task.cronExpr);
            ScheduledFuture<?> future = scheduler.schedule(
                    () -> safeTrigger(task.id), trigger);
            jobs.put(task.id, future);
            log.info("cron scheduled for task {} [{}]", task.id, task.cronExpr);
        } catch (IllegalArgumentException e) {
            log.error("invalid cron '{}' for task {}", task.cronExpr, task.id);
        }
    }

    private void safeTrigger(long taskId) {
        try {
            taskControlService.controlTask(taskId, "start");
        } catch (Exception e) {
            log.error("cron trigger task {} failed", taskId, e);
        }
    }

    private void cancel(long taskId) {
        ScheduledFuture<?> future = jobs.remove(taskId);
        if (future != null) future.cancel(false);
    }

    private void reloadAll() {
        for (Task task : taskMapper.selectList(
                new QueryWrapper<Task>().eq("status", 1).eq("schedule_type", "CRON"))) {
            schedule(task);
        }
    }
}
