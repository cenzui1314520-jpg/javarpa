package com.rpa.engine.script;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Downloads script packages, verifies md5 and installs them in versioned dirs. */
public class ScriptDownloader {
    private static final int TIMEOUT_MS = 30_000;

    private final Context context;
    private final OkHttpClient client = new OkHttpClient();

    public ScriptDownloader(Context context) {
        this.context = context;
    }

    public byte[] download(String baseUrl, String relativeUrl, String sn, String secret)
            throws IOException {
        String url = baseUrl.replaceAll("/+$", "") + relativeUrl;
        Request request = new Request.Builder()
                .url(url)
                .header("X-Device-Sn", sn)
                .header("X-Device-Secret", secret)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("下载失败 HTTP " + response.code());
            }
            return response.body().bytes();
        }
    }

    public static String md5Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(data)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Extracts zip into target dir, guarding against zip-slip path traversal. */
    public static void unzip(byte[] zipBytes, File targetDir) throws IOException {
        File staging = new File(targetDir.getParentFile(),
                targetDir.getName() + ".staging_" + System.currentTimeMillis());
        if (!staging.mkdirs() && !staging.exists()) {
            throw new IOException("无法创建解压目录");
        }
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                File out = new File(staging, entry.getName());
                String canonical = out.getCanonicalPath();
                if (!canonical.startsWith(staging.getCanonicalPath() + File.separator)
                        && !canonical.equals(staging.getCanonicalPath())) {
                    throw new IOException("非法的 zip 条目: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                out.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    int n;
                    while ((n = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, n);
                    }
                }
            }
        }
        deleteRecursive(targetDir);
        if (!staging.renameTo(targetDir)) {
            throw new IOException("脚本目录替换失败");
        }
    }

    public static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursive(c);
        }
        file.delete();
    }
}
