package com.rpa.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("script_version")
public class ScriptVersion {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long scriptId;
    public Integer versionCode;
    public String versionName;
    public String filePath;
    public String fileSha256;
    public Long fileSize;
    public Integer status;
    public String changelog;
    public String createdBy;
    public LocalDateTime createdAt;
}
