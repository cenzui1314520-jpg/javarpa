package com.rpa.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("publish_record")
public class PublishRecord {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long scriptId;
    public Integer versionCode;
    public String targetType;
    public String targetValue;
    public String operator;
    public LocalDateTime createdAt;
}
