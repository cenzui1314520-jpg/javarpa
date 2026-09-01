package com.rpa.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("api_token")
public class ApiToken {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String name;
    public String prefix;
    public String tokenHash;
    public Integer status;
    public LocalDateTime lastUsedAt;
    public LocalDateTime createdAt;
}
