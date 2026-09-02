package com.rpa.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("admin_user")
public class AdminUser {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String username;
    public String passwordHash;
    public String nickname;
    public String role;
    public Integer status;
    public LocalDateTime createdAt;
}
