package com.rpa.server.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

public final class DigestUtil {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHANUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private DigestUtil() {}

    public static String md5Hex(byte[] data) {
        return digest("MD5", data);
    }

    /** 流式计算文件 MD5，避免整包读入内存。 */
    public static String md5Hex(java.nio.file.Path file) {
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(String data) {
        return digest("SHA-256", data.getBytes(StandardCharsets.UTF_8));
    }

    public static String randomToken(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHANUM[RANDOM.nextInt(ALPHANUM.length)]);
        }
        return sb.toString();
    }

    private static String digest(String algo, byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance(algo);
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
