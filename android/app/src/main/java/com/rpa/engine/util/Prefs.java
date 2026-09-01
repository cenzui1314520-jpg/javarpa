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
        return sp(ctx).getString(KEY_SECRET, "");
    }

    public static void save(Context ctx, String server, String sn, String secret) {
        sp(ctx).edit()
                .putString(KEY_SERVER, server)
                .putString(KEY_SN, sn)
                .putString(KEY_SECRET, secret)
                .apply();
    }

    public static boolean configured(Context ctx) {
        return !serverUrl(ctx).isEmpty() && !deviceSn(ctx).isEmpty() && !secret(ctx).isEmpty();
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }
}
