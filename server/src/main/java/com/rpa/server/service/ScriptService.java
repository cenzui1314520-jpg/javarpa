package com.rpa.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rpa.server.common.ApiException;
import com.rpa.server.common.DigestUtil;
import com.rpa.server.entity.Script;
import com.rpa.server.entity.ScriptVersion;
import com.rpa.server.mapper.ScriptMapper;
import com.rpa.server.mapper.ScriptVersionMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Service
public class ScriptService {
    private final ScriptMapper scriptMapper;
    private final ScriptVersionMapper versionMapper;

    @Value("${rpa.upload-dir:./data/scripts}")
    private String uploadDir;

    public ScriptService(ScriptMapper scriptMapper, ScriptVersionMapper versionMapper) {
        this.scriptMapper = scriptMapper;
        this.versionMapper = versionMapper;
    }

    public Script create(String name, String pkgName, String description) {
        if (name == null || name.isBlank() || pkgName == null || pkgName.isBlank()) {
            throw new ApiException("name 与 pkgName 不能为空");
        }
        if (!pkgName.matches("[a-zA-Z0-9._-]{1,64}")) {
            throw new ApiException("pkgName 仅允许字母数字._- 且不超过 64 字符");
        }
        Long exists = scriptMapper.selectCount(new QueryWrapper<Script>().eq("pkg_name", pkgName));
        if (exists > 0) throw new ApiException("pkgName 已存在");
        Script s = new Script();
        s.name = name;
        s.pkgName = pkgName;
        s.description = description;
        s.stableVersionCode = 0;
        scriptMapper.insert(s);
        return s;
    }

    public void update(long id, String name, String description) {
        Script s = require(id);
        Script upd = new Script();
        upd.id = id;
        upd.name = name != null ? name : s.name;
        upd.description = description;
        scriptMapper.updateById(upd);
    }

    public void delete(long id) {
        require(id);
        scriptMapper.deleteById(id);
        versionMapper.delete(new QueryWrapper<ScriptVersion>().eq("script_id", id));
    }

    public List<Script> list() {
        return scriptMapper.selectList(new QueryWrapper<Script>().orderByDesc("id"));
    }

    public Script require(long id) {
        Script s = scriptMapper.selectById(id);
        if (s == null) throw new ApiException(404, "脚本不存在");
        return s;
    }

    public ScriptVersion uploadVersion(long scriptId, MultipartFile file, int versionCode,
                                       String versionName, String changelog, String operator) {
        Script script = require(scriptId);
        if (file == null || file.isEmpty()) throw new ApiException("请上传脚本 zip 包");
        if (versionCode <= 0) throw new ApiException("versionCode 必须为正整数");
        Long exists = versionMapper.selectCount(new QueryWrapper<ScriptVersion>()
                .eq("script_id", scriptId).eq("version_code", versionCode));
        if (exists > 0) throw new ApiException("该版本号已存在");

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ApiException("读取上传文件失败");
        }
        validateZip(bytes);

        String md5 = DigestUtil.md5Hex(bytes);
        String relativePath = "/files/scripts/" + script.pkgName + "/" + versionCode + ".zip";
        Path target = Paths.get(System.getProperty("user.dir"),
                uploadDir.startsWith("/") ? uploadDir.substring(1) : uploadDir,
                script.pkgName, versionCode + ".zip");
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new ApiException(500, "保存脚本文件失败: " + e.getMessage());
        }

        ScriptVersion v = new ScriptVersion();
        v.scriptId = scriptId;
        v.versionCode = versionCode;
        v.versionName = versionName;
        v.filePath = relativePath;
        v.fileMd5 = md5;
        v.fileSize = (long) bytes.length;
        v.status = 1;
        v.changelog = changelog;
        v.createdBy = operator;
        versionMapper.insert(v);
        return v;
    }

    /** Zip must contain root main.js + config.json, no path traversal entries. */
    private void validateZip(byte[] bytes) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("rpa-upload-", ".zip");
            Files.write(tmp, bytes);
            try (ZipFile zip = new ZipFile(tmp.toFile())) {
                boolean hasMain = false, hasConfig = false;
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    String name = e.getName();
                    if (name.contains("..") || name.startsWith("/")) {
                        throw new ApiException("非法的 zip 条目: " + name);
                    }
                    if (name.equals("main.js")) hasMain = true;
                    if (name.equals("config.json")) hasConfig = true;
                }
                if (!hasMain) throw new ApiException("zip 包根目录缺少 main.js");
                if (!hasConfig) throw new ApiException("zip 包根目录缺少 config.json");
            }
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException("zip 包解析失败");
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        }
    }

    public List<ScriptVersion> versions(long scriptId) {
        return versionMapper.selectList(new QueryWrapper<ScriptVersion>()
                .eq("script_id", scriptId).orderByDesc("version_code"));
    }

    public ScriptVersion findVersion(long scriptId, int versionCode) {
        return versionMapper.selectOne(new QueryWrapper<ScriptVersion>()
                .eq("script_id", scriptId).eq("version_code", versionCode).last("LIMIT 1"));
    }
}
