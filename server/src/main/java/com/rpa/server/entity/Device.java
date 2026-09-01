package com.rpa.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("device")
public class Device {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String deviceSn;
    public String name;
    public Long groupId;
    public String model;
    public String brand;
    public String androidVersion;
    public Integer sdkInt;
    public String appVersion;
    public String engineVersion;
    public String secret;
    public Integer status;
    public Integer online;
    public LocalDateTime lastActiveAt;
    public LocalDateTime createdAt;
}
