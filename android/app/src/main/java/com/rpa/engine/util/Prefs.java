package com.rpa.engine.util;

import android.content.Context;
import android.content.SharedPreferences;

public class Prefs {
    private static final String NAME = "rpa_prefs";
    private static final String KEY_SERVER = "server_url";
    private static final String KEY_SN = "device_sn";
    private static final String KEY_SECRET = "device_secret";

    public static String serverUrl(Context ctx) {
        return sp(ctx).getString(KEY_SERVER, "");
    }

    public static String deviceSn(Context ctx) {
        return sp(ctx).getString(KEY_SN, "");
    }

    public static String secret(Context ctx) {
        String stored = sp(ctx).getString(KEY_SECRET, "");
        if (stored.isEmpty()) return stored;
        if (KeystoreCrypto.isEncrypted(stored)) {
            String plain = KeystoreCrypto.decrypt(stored);
            return plain != null ? plain : ""; // Keystore 异常时不吐密文
        }
        // 历史明文：读出成功即透明升级为密文存储
        String encrypted = KeystoreCrypto.encrypt(stored);
        if (encrypted != null) {
            sp(ctx).edit().putString(KEY_SECRET, encrypted).apply();
        }
        return stored;
    }

    public static void save(Context ctx, String server, String sn, String secret) {
        // 设备唯一凭据加密落盘；Keystore 异常时降级明文保证可用
        String stored = KeystoreCrypto.encrypt(secret);
        sp(ctx).edit()
                .putString(KEY_SERVER, server)
                .putString(KEY_SN, sn)
                .putString(KEY_SECRET, stored != null ? stored : secret)
                .apply();
    }

    public static boolean configured(Context ctx) {
        return !serverUrl(ctx).isEmpty() && !deviceSn(ctx).isEmpty() && !secret(ctx).isEmpty();
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }
}
