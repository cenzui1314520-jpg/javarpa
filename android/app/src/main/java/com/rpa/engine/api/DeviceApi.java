package com.rpa.engine.api;

import android.os.Build;

import com.rpa.engine.accessibility.UiOperator;

import java.util.HashMap;
import java.util.Map;

/** JS global `device` object. */
public class DeviceApi {

    public Map<String, Object> getInfo() {
        Map<String, Object> m = new HashMap<>();
        m.put("model", Build.MODEL);
        m.put("brand", Build.BRAND);
        m.put("sdkInt", Build.VERSION.SDK_INT);
        m.put("androidVersion", Build.VERSION.RELEASE);
        m.put("screen", UiOperator.screen());
        return m;
    }

    public int getWidth() {
        return (int) UiOperator.screen().get("width");
    }

    public int getHeight() {
        return (int) UiOperator.screen().get("height");
    }
}
