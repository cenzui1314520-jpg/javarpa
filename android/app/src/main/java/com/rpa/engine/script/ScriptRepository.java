package com.rpa.engine.script;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Manages locally installed scripts under files/scripts/{scriptId}/{versionCode}/. */
public class ScriptRepository {
    private final Context context;
    private final ScriptDownloader downloader;
    // CMD_UPDATE_SCRIPT 安装线程与 CMD_START 执行线程可能并发安装同一版本，串行化防止目录损坏
    private final Object installLock = new Object();

    public ScriptRepository(Context context) {
        this.context = context;
        this.downloader = new ScriptDownloader(context);
    }

    public File baseDir() {
        return new File(context.getFilesDir(), "scripts");
    }

    public File scriptDir(long scriptId, int versionCode) {
        return new File(baseDir(), scriptId + "/" + versionCode);
    }

    /** 上报全部已安装版本（而非仅最大版本）：回滚到旧版本后云端按"目标版本是否已装"判定，不再重推。 */
    public List<JSONObject> installedVersions() {
        List<JSONObject> result = new ArrayList<>();
        File base = baseDir();
        File[] scriptDirs = base.listFiles(File::isDirectory);
        if (scriptDirs == null) return result;
        for (File dir : scriptDirs) {
            File[] versions = dir.listFiles(File::isDirectory);
            if (versions == null) continue;
            for (File v : versions) {
                try {
                    long scriptId = Long.parseLong(dir.getName());
                    int versionCode = Integer.parseInt(v.getName());
                    JSONObject o = new JSONObject();
                    o.put("scriptId", scriptId);
                    o.put("versionCode", versionCode);
                    result.add(o);
                } catch (Exception ignored) {
                }
            }
        }
        return result;
    }

    public boolean hasVersion(long scriptId, int versionCode) {
        File main = new File(scriptDir(scriptId, versionCode), "main.js");
        return main.exists();
    }

    /** Ensures the exact version exists locally; downloads and installs when missing. */
    public void ensureInstalled(long scriptId, int versionCode, String relativeUrl, String sha256,
                                String baseUrl, String sn, String secret) throws IOException {
        synchronized (installLock) {
            if (verifyLocal(scriptId, versionCode, sha256)) return;
            if (sha256 == null) {
                // 协议要求携带 sha256；缺失说明服务端异常，告警但保持兼容继续安装
                android.util.Log.w("ScriptRepository",
                        "CMD_START/CMD_UPDATE_SCRIPT 未携带 sha256,跳过完整性校验: script=" + scriptId);
            }
            byte[] zip = downloader.download(baseUrl, relativeUrl, sn, secret);
            String actual = ScriptDownloader.sha256Hex(zip);
            if (sha256 != null && !sha256.equalsIgnoreCase(actual)) {
                throw new IOException("sha256 校验失败: 期望 " + sha256 + " 实际 " + actual);
            }
            ScriptDownloader.unzip(zip, scriptDir(scriptId, versionCode));
            writeText(new File(scriptDir(scriptId, versionCode), ".sha256"), actual);
        }
    }

    private boolean verifyLocal(long scriptId, int versionCode, String sha256) {
        File dir = scriptDir(scriptId, versionCode);
        File main = new File(dir, "main.js");
        if (!main.exists()) return false;
        if (sha256 == null) return true;
        File meta = new File(dir, ".sha256");
        if (!meta.exists()) return false;
        try {
            return sha256.equalsIgnoreCase(readText(meta).trim());
        } catch (IOException e) {
            return false;
        }
    }

    public String readMainJs(long scriptId, int versionCode) throws IOException {
        File main = new File(scriptDir(scriptId, versionCode), "main.js");
        if (!main.exists()) throw new IOException("main.js 不存在: script=" + scriptId
                + " version=" + versionCode);
        return readText(main);
    }

    private static String readText(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                bos.write(buffer, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void writeText(File file, String content) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
