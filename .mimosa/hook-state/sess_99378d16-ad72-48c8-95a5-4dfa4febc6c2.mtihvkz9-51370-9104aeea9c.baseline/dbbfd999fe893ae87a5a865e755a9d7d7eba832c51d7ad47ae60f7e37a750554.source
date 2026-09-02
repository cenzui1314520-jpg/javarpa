package com.rpa.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("task_device")
public class TaskDevice {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long taskId;
    public Long deviceId;
    public String status;
    public Integer retryCount;
    public Integer successCount;
    public Integer failCount;
    public LocalDateTime lastRunAt;
    public LocalDateTime updatedAt;
}
