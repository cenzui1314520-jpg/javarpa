package com.rpa.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("device_group")
public class DeviceGroup {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String name;
    public String remark;
    public LocalDateTime createdAt;
}
