package com.rpa.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("device_log")
public class DeviceLog {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long deviceId;
    public Long taskId;
    public String level;
    public String tag;
    public String content;
    public LocalDateTime logTime;
    public LocalDateTime createdAt;
}
