package com.rpa.engine.api;

import com.rpa.engine.accessibility.UiOperator;
import com.rpa.engine.engine.RhinoScriptEngine;

import java.io.File;

/** JS global `auto` object: the whole automation surface exposed to scripts. */
public class AutoApi {
    private final RhinoScriptEngine.Host host;
    private final ReportApi report = new ReportApi();
    private File baseDir;

    public AutoApi(RhinoScriptEngine.Host host) {
        this.host = host;
    }

    /** Script package dir, used as the base for screenshot save/template paths. */
    public void setBaseDir(File baseDir) {
        this.baseDir = baseDir;
    }

    /** Captures the screen (Android 11+). Returns null when unsupported. */
    public ImageApi screenshot() {
        android.graphics.Bitmap bmp = UiOperator.takeScreenshot();
        if (bmp == null) return null;
        File dir = baseDir != null ? baseDir
                : new File(com.rpa.engine.App.get().getFilesDir(), "shots");
        return new ImageApi(bmp, dir, host);
    }

    /** 读取脚本包内文本资源（如 res/words.txt），路径不得越出包目录。 */
    public String readText(String relPath) throws java.io.IOException {
        if (baseDir == null) return null;
        File f = new File(baseDir, relPath);
        String canonical = f.getCanonicalPath();
        String base = baseDir.getCanonicalPath();
        if (!canonical.startsWith(base + File.separator) && !canonical.equals(base)) {
            throw new java.io.IOException("路径越界: " + relPath);
        }
        if (!f.exists()) return null;
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                bos.write(buffer, 0, n);
            }
        }
        return new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    // ---------- selectors ----------
    public SelectorApi text(String text) {
        return new SelectorApi().text(text);
    }

    public SelectorApi textContains(String s) {
        return new SelectorApi().textContains(s);
    }

    public SelectorApi id(String idSuffix) {
        return new SelectorApi().id(idSuffix);
    }

    public SelectorApi desc(String desc) {
        return new SelectorApi().desc(desc);
    }

    public boolean clickText(String text) throws InterruptedException {
        NodeApi n = new SelectorApi().text(text).findOne(8000);
        return n != null && n.click();
    }

    public boolean clickId(String idSuffix) throws InterruptedException {
        NodeApi n = new SelectorApi().id(idSuffix).findOne(8000);
        return n != null && n.click();
    }

    // ---------- gestures ----------
    public boolean tap(int x, int y) {
        return UiOperator.tap(x, y);
    }

    public boolean swipe(int x1, int y1, int x2, int y2) {
        return swipe(x1, y1, x2, y2, 300);
    }

    public boolean swipe(int x1, int y1, int x2, int y2, int durationMs) {
        return UiOperator.swipe(x1, y1, x2, y2, durationMs);
    }

    public boolean back() {
        return UiOperator.back();
    }

    public boolean home() {
        return UiOperator.home();
    }

    public boolean launch(String pkg) {
        return UiOperator.launchApp(pkg);
    }

    // ---------- lifecycle ----------
    public boolean isAccessibilityOn() {
        return com.rpa.engine.accessibility.AutoAccessibilityService.isRunning();
    }

    public boolean isPaused() {
        return host != null && host.isPaused();
    }

    /** Blocks while the task is paused; throws if stop was requested. */
    public void waitIfPaused() throws InterruptedException {
        if (host == null) return;
        while (host.isPaused() && !host.isStopRequested()) {
            Thread.sleep(200);
        }
        if (host.isStopRequested()) {
            throw new InterruptedException("stopped");
        }
    }

    public void stop() {
        if (host != null) host.requestStop();
    }

    public ReportApi getReport() {
        return report;
    }
}
