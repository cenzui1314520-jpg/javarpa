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
