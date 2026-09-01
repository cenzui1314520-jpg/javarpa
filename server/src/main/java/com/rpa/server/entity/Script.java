package com.rpa.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("script")
public class Script {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String name;
    public String pkgName;
    public String description;
    public Integer stableVersionCode;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
