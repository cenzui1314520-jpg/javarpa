package com.rpa.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;

@TableName("stats_daily")
public class StatsDaily {
    @TableId(type = IdType.AUTO)
    public Long id;
    public LocalDate statDate;
    public Long taskId;
    public Long scriptId;
    public Integer totalExec;
    public Integer successExec;
    public Integer failExec;
    public Integer successCnt;
    public Integer failCnt;
}
