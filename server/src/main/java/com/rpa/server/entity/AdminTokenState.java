package com.rpa.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("admin_token_state")
public class AdminTokenState {
    @TableId(type = IdType.INPUT)
    public Long adminId;
    public Long passwordChangedAt;
}
