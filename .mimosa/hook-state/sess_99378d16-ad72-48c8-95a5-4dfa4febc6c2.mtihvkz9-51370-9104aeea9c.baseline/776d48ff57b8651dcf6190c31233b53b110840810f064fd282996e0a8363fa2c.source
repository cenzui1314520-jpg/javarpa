package com.rpa.engine.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/** Entry point of all UI automation. Must be enabled in system accessibility settings. */
public class AutoAccessibilityService extends AccessibilityService {

    private static volatile AutoAccessibilityService instance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    public static boolean isRunning() {
        return instance != null;
    }

    public static AutoAccessibilityService get() {
        return instance;
    }
}
