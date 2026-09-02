package com.rpa.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("task")
public class Task {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String name;
    public Long scriptId;
    public Integer versionCode;
    public String paramsJson;
    public String scheduleType;
    public String cronExpr;
    public Integer maxRetries;
    public Integer status;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
