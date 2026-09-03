package com.rpa.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rpa.server.common.ApiException;
import com.rpa.server.common.DigestUtil;
import com.rpa.server.entity.Device;
import com.rpa.server.entity.Script;
import com.rpa.server.entity.ScriptVersion;
import com.rpa.server.entity.Task;
import com.rpa.server.entity.TaskDevice;
import com.rpa.server.mapper.ScriptMapper;
import com.rpa.server.mapper.ScriptVersionMapper;
import com.rpa.server.mapper.TaskDeviceMapper;
import com.rpa.server.mapper.TaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Service
public class ScriptService {
    private static final Logger log = LoggerFactory.getLogger(ScriptService.class);

    private final ScriptMapper scriptMapper;
    private final ScriptVersionMapper versionMapper;
    private final com.rpa.server.mapper.PublishRecordMapper publishRecordMapper;
    private final TaskMapper taskMapper;
    private final TaskDeviceMapper taskDeviceMapper;
    private final PublishService publishService;

    @Value("${rpa.upload-dir:./data/scripts}")
    private String uploadDir;

    public ScriptService(ScriptMapper scriptMapper, ScriptVersionMapper versionMapper,
                         com.rpa.server.mapper.PublishRecordMapper publishRecordMapper,
                         TaskMapper taskMapper, TaskDeviceMapper taskDeviceMapper,
                         PublishService publishService) {
        this.scriptMapper = scriptMapper;
        this.versionMapper = versionMapper;
        this.publishRecordMapper = publishRecordMapper;
        this.taskMapper = taskMapper;
        this.taskDeviceMapper = taskDeviceMapper;
        this.publishService = publishService;
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
        Script s = require(id);
        scriptMapper.deleteById(id);
        versionMapper.delete(new QueryWrapper<ScriptVersion>().eq("script_id", id));
        // 发布记录一并清理，避免残留记录影响同名脚本重建后的灰度判定
        publishRecordMapper.delete(new QueryWrapper<com.rpa.server.entity.PublishRecord>()
                .eq("script_id", id));
        Path dir = scriptDir(s.pkgName);
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException e) {
            log.warn("clean script dir {} failed: {}", dir, e.getMessage());
        }
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
        if (file == null || file.isEmpty()) throw new ApiException("请上传脚本 zip 包");
        try {
            return storeVersion(scriptId, file.getBytes(), versionCode, versionName, changelog, operator);
        } catch (IOException e) {
            throw new ApiException(500, "读取上传文件失败");
        }
    }

    /** 在线编辑创建新版本：files 每项 {name, content(UTF-8 文本)}，versionCode 为空自动递增。
     *  baseVersionCode 指定基准版本时，其包内二进制文件原样保留，文本文件以本次提交为准（可删减）。 */
    public ScriptVersion uploadVersionFromFiles(long scriptId, List<Map<String, String>> files,
                                                Integer baseVersionCode, Integer versionCode,
                                                String versionName, String changelog, String operator) {
        if (files == null || files.isEmpty()) throw new ApiException("文件列表不能为空");
        boolean hasMain = false, hasConfig = false;
        for (Map<String, String> f : files) {
            String name = f.get("name");
            String content = f.get("content");
            if (name == null || name.isBlank()) throw new ApiException("文件名不能为空");
            if (name.contains("..") || name.startsWith("/") || name.contains(":") || name.indexOf(92) >= 0
                    || name.startsWith("./") || name.contains("//")) {
                throw new ApiException("非法文件名: " + name);
            }
            if (content == null) content = "";
            if (content.length() > 10 * 1024 * 1024) throw new ApiException("文件过大: " + name);
            if (name.equals("main.js")) hasMain = true;
            if (name.equals("config.json")) hasConfig = true;
        }
        if (!hasMain) throw new ApiException("缺少 main.js");
        if (!hasConfig) throw new ApiException("缺少 config.json");
        String cfg = files.stream().filter(f -> "config.json".equals(f.get("name")))
                .findFirst().orElseThrow().get("content");
        try {
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(cfg);
        } catch (Exception e) {
            throw new ApiException("config.json 不是合法 JSON: " + e.getMessage());
        }
        byte[] zip = buildZip(scriptId, baseVersionCode, files);
        return storeVersion(scriptId, zip, versionCode, versionName, changelog, operator);
    }

    /** 读取某版本 zip 内文件列表；文本文件返回内容，二进制仅返回元信息。 */
    public List<Map<String, Object>> readVersionFiles(long scriptId, int versionCode) {
        Script script = require(scriptId);
        ScriptVersion v = findVersion(scriptId, versionCode);
        if (v == null) throw new ApiException(404, "版本不存在: v" + versionCode);
        Path zipPath = scriptDir(script.pkgName).resolve(versionCode + ".zip");
        if (!Files.exists(zipPath)) throw new ApiException(500, "脚本包文件缺失: " + zipPath);
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                byte[] bytes;
                try (var in = zip.getInputStream(e)) {
                    bytes = in.readAllBytes();
                }
                Map<String, Object> m = new java.util.HashMap<>();
                m.put("name", e.getName());
                m.put("size", bytes.length);
                if (isTextFile(e.getName(), bytes)) {
                    m.put("text", true);
                    m.put("content", new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                } else {
                    m.put("text", false);
                }
                result.add(m);
            }
        } catch (IOException e) {
            throw new ApiException(500, "脚本包读取失败: " + e.getMessage());
        }
        result.sort(java.util.Comparator.comparing(m -> String.valueOf(m.get("name"))));
        return result;
    }

    private static final java.util.Set<String> TEXT_EXTS = java.util.Set.of(
            "js", "json", "txt", "md", "csv", "xml", "html", "htm", "css", "yml", "yaml", "properties", "log");

    private boolean isTextFile(String name, byte[] bytes) {
        String n = name.toLowerCase();
        int dot = n.lastIndexOf('.');
        String ext = dot < 0 ? "" : n.substring(dot + 1);
        if (!TEXT_EXTS.contains(ext)) return false;
        try {
            java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] buildZip(long scriptId, Integer baseVersionCode, List<Map<String, String>> files) {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            // 基准版本的二进制文件原样保留（编辑器无法编辑，也不应丢失）
            if (baseVersionCode != null) {
                Script script = require(scriptId);
                ScriptVersion base = findVersion(scriptId, baseVersionCode);
                if (base == null) throw new ApiException(404, "基准版本不存在: v" + baseVersionCode);
                Path baseZip = scriptDir(script.pkgName).resolve(baseVersionCode + ".zip");
                if (!Files.exists(baseZip)) throw new ApiException(500, "基准脚本包文件缺失");
                try (ZipFile zip = new ZipFile(baseZip.toFile())) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry e = entries.nextElement();
                        byte[] data;
                        try (var in = zip.getInputStream(e)) {
                            data = in.readAllBytes();
                        }
                        if (e.isDirectory() || isTextFile(e.getName(), data)) continue;
                        zos.putNextEntry(new ZipEntry(e.getName()));
                        zos.write(data);
                        zos.closeEntry();
                    }
                }
            }
            for (Map<String, String> f : files) {
                zos.putNextEntry(new ZipEntry(f.get("name")));
                String content = f.get("content") == null ? "" : f.get("content");
                zos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        } catch (IOException e) {
            throw new ApiException(500, "打包失败: " + e.getMessage());
        }
        return baos.toByteArray();
    }

    /** zip 字节落库为新版本；versionCode 为空时自动取最大版本 +1。 */
    private ScriptVersion storeVersion(long scriptId, byte[] zipBytes, Integer versionCode,
                                       String versionName, String changelog, String operator) {
        Script script = require(scriptId);
        int vc = versionCode == null
                ? versionMapper.selectList(new QueryWrapper<ScriptVersion>().eq("script_id", scriptId))
                        .stream().mapToInt(x -> x.versionCode).max().orElse(0) + 1
                : versionCode;
        if (vc <= 0) throw new ApiException("versionCode 必须为正整数");
        Long exists = versionMapper.selectCount(new QueryWrapper<ScriptVersion>()
                .eq("script_id", scriptId).eq("version_code", vc));
        if (exists > 0) throw new ApiException("该版本号已存在");

        String relativePath = "/files/scripts/" + script.pkgName + "/" + vc + ".zip";
        Path target = scriptDir(script.pkgName).resolve(vc + ".zip");
        // 流式落盘到临时文件，避免整包读入堆内存；并发上传同版本时各自写独立 tmp 互不覆盖
        Path tmp = null;
        try {
            Files.createDirectories(target.getParent());
            tmp = Files.createTempFile(target.getParent(), vc + ".zip.", ".part");
            Files.write(tmp, zipBytes);
            long size = Files.size(tmp);
            if (size <= 0) throw new ApiException("上传内容为空");
            String sha256 = DigestUtil.sha256Hex(tmp);
            validateZip(tmp);

            ScriptVersion v = new ScriptVersion();
            v.scriptId = scriptId;
            v.versionCode = vc;
            v.versionName = versionName;
            v.filePath = relativePath;
            v.fileSha256 = sha256;
            v.fileSize = size;
            v.status = 1;
            v.changelog = changelog;
            v.createdBy = operator;
            try {
                // 先占住唯一版本号再落正式文件；失败只清理自己的 tmp，绝不触碰已有版本文件
                versionMapper.insert(v);
            } catch (Exception e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
            atomicMove(tmp, target);
            return v;
        } catch (ApiException e) {
            deleteQuietly(tmp);
            throw e;
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new ApiException(500, "保存脚本文件失败");
        } catch (Exception e) {
            deleteQuietly(tmp);
            throw e;
        }
    }

    private void atomicMove(Path src, Path dst) throws IOException {
        try {
            Files.move(src, dst, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteQuietly(Path p) {
        if (p == null) return;
        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
    }

    private Path scriptDir(String pkgName) {
        // 绝对路径必须保持绝对，否则与 WebConfig 静态映射(file:/...)的下载目录错位，设备下载 404
        Path base = uploadDir.startsWith("/")
                ? Paths.get(uploadDir)
                : Paths.get(System.getProperty("user.dir"), uploadDir);
        return base.resolve(pkgName);
    }

    /** Zip must contain root main.js + config.json, no path traversal entries. */
    private void validateZip(Path zipFile) {
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            boolean hasMain = false, hasConfig = false;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                // 拦截路径穿越与 Windows 盘符/反斜杠条目
                if (name.contains("..") || name.startsWith("/") || name.contains(":") || name.contains("\\")) {
                    throw new ApiException("非法的 zip 条目: " + name);
                }
                if (name.equals("main.js")) hasMain = true;
                if (name.equals("config.json")) hasConfig = true;
            }
            if (!hasMain) throw new ApiException("zip 包根目录缺少 main.js");
            if (!hasConfig) throw new ApiException("zip 包根目录缺少 config.json");
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException("zip 包解析失败");
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

    /** 设备仅允许下载：灰度目标版本 / stable 版本 / 其进行中任务所需的版本。 */
    public boolean canDeviceDownload(Device device, String pkgName, int versionCode) {
        Script script = scriptMapper.selectOne(new QueryWrapper<Script>()
                .eq("pkg_name", pkgName).last("LIMIT 1"));
        if (script == null) return false;
        if (publishService.resolveTargetVersion(script.id, device) == versionCode) return true;
        if (script.stableVersionCode != null && script.stableVersionCode == versionCode) return true;
        List<TaskDevice> active = taskDeviceMapper.selectList(new QueryWrapper<TaskDevice>()
                .eq("device_id", device.id).in("status", "PENDING", "RUNNING", "PAUSED"));
        if (active.isEmpty()) return false;
        List<Long> taskIds = active.stream().map(td -> td.taskId).toList();
        Long used = taskMapper.selectCount(new QueryWrapper<Task>()
                .in("id", taskIds).eq("script_id", script.id).eq("version_code", versionCode));
        return used != null && used > 0;
    }
}
