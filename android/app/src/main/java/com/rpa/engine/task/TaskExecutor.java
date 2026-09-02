package com.rpa.engine.task;

import android.content.Context;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.rpa.engine.api.AutoApi;
import com.rpa.engine.engine.RhinoScriptEngine;
import com.rpa.engine.script.ScriptRepository;
import com.rpa.engine.util.Prefs;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;

/** Runs one cloud task at a time in a dedicated thread; reports status via WS. */
public class TaskExecutor {

    public interface Reporter {
        boolean send(String type, JSONObject data);
        boolean isConnected();
    }

    private final Context context;
    private final ScriptRepository repository;
    private final RhinoScriptEngine engine = new RhinoScriptEngine();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Queue<JSONObject> pendingResults = new ArrayDeque<>();
    // taskId -> 最近一次 CMD_START 完整参数，CMD_RESTART 只带 taskId 时复用
    private final Map<Long, JSONObject> lastStartData = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, JSONObject> eldest) {
            return size() > 16;
        }
    };

    private volatile Reporter reporter;
    private volatile RunContext current;

    public TaskExecutor(Context context, ScriptRepository repository) {
        this.context = context;
        this.repository = repository;
    }

    public void setReporter(Reporter reporter) {
        this.reporter = reporter;
    }

    private class RunContext implements RhinoScriptEngine.Host {
        final long taskId;
        final long scriptId;
        final int versionCode;
        final String paramsJson;
        final String url;
        final String md5;
        final long maxRuntimeMs; // CMD_START 可选下发，0 表示不限
        final long startedAt = System.currentTimeMillis();
        final AutoApi auto = new AutoApi(this);
        volatile Thread thread;
        volatile boolean paused;
        volatile boolean stopRequested;
        java.util.Timer watchdog; // guarded by TaskExecutor.this

        RunContext(JSONObject data) throws Exception {
            this.taskId = data.getLong("taskId");
            this.scriptId = data.getLong("scriptId");
            this.versionCode = data.getInt("versionCode");
            this.paramsJson = data.optString("params", "{}");
            this.url = data.optString("url", "");
            this.md5 = data.optString("md5", null);
            this.maxRuntimeMs = Math.max(0, data.optLong("maxRuntimeSec", 0)) * 1000L;
        }

        @Override
        public boolean isPaused() {
            return paused;
        }

        @Override
        public boolean isStopRequested() {
            return stopRequested;
        }

        @Override
        public void requestStop() {
            stopRequested = true;
        }

        @Override
        public void onLog(String level, String content) {
            sendLog(taskId, level, "script", content);
        }

        @Override
        public void onToast(String message) {
            showToast(message);
        }
    }

    public void start(JSONObject data) {
        RunContext rc;
        try {
            rc = new RunContext(data);
        } catch (Exception e) {
            sendResult(buildResult(data.optLong("taskId", 0), "FAILED",
                    0, 0, "指令参数不完整: " + e.getMessage(), 0));
            return;
        }
        synchronized (lastStartData) {
            lastStartData.put(rc.taskId, data);
        }
        requestStopCurrent(3000);
        synchronized (this) {
            current = rc;
        }
        rc.thread = new Thread(() -> runScript(rc), "rpa-task-" + rc.taskId);
        rc.thread.start();
        startWatchdog(rc);
    }

    /** 超时未退出的旧线程标记为孤儿并分离，不再阻塞新任务（孤儿靠指令观察器自灭）。 */
    private void requestStopCurrent(long timeoutMs) {
        RunContext rc;
        synchronized (this) {
            rc = current;
            if (rc == null) return;
            rc.stopRequested = true;
            if (rc.thread != null) rc.thread.interrupt();
        }
        Thread t = rc.thread;
        if (t != null) {
            try {
                t.join(timeoutMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        boolean exited = t == null || !t.isAlive();
        if (!exited) {
            android.util.Log.w("TaskExecutor",
                    "old task " + rc.taskId + " not stopped in " + timeoutMs + "ms, detach as orphan");
        }
        synchronized (this) {
            if (current == rc) current = null;
        }
    }

    // 断网期间服务器无法下发 CMD_STOP，看门狗兜底防止脚本无限占用设备
    private synchronized void startWatchdog(RunContext rc) {
        if (rc.maxRuntimeMs <= 0) return;
        stopWatchdog(rc);
        rc.watchdog = new java.util.Timer("rpa-watchdog-" + rc.taskId);
        rc.watchdog.schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                android.util.Log.w("TaskExecutor", "task " + rc.taskId + " exceeded max runtime, stopping");
                rc.stopRequested = true;
                if (rc.thread != null) rc.thread.interrupt();
            }
        }, rc.maxRuntimeMs);
    }

    private synchronized void stopWatchdog(RunContext rc) {
        if (rc.watchdog != null) {
            rc.watchdog.cancel();
            rc.watchdog = null;
        }
    }

    private void runScript(RunContext rc) {
        sendResult(buildResult(rc.taskId, "RUNNING", 0, 0, null, 0));
        String status;
        String error = null;
        try {
            String base = Prefs.serverUrl(context);
            repository.ensureInstalled(rc.scriptId, rc.versionCode, rc.url, rc.md5,
                    base, Prefs.deviceSn(context), Prefs.secret(context));
            String source = repository.readMainJs(rc.scriptId, rc.versionCode);
            engine.execute(source, rc.paramsJson, rc, rc.auto,
                    repository.scriptDir(rc.scriptId, rc.versionCode));
            status = rc.stopRequested ? "STOPPED" : "SUCCESS";
        } catch (InterruptedException e) {
            status = "STOPPED";
        } catch (RhinoScriptEngine.ScriptStopException e) {
            status = "STOPPED";
        } catch (Exception e) {
            // Rhino 会把 js_* 方法抛出的 InterruptedException 包装为 WrappedException，需展开识别
            if (rc.stopRequested && isStopInterruption(e)) {
                status = "STOPPED";
            } else {
                status = "FAILED";
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
        double duration = (System.currentTimeMillis() - rc.startedAt) / 1000.0;
        int ok = rc.auto.getReport().getOk();
        int fail = rc.auto.getReport().getFail();
        sendResult(buildResult(rc.taskId, status, ok, fail, error, duration));
        stopWatchdog(rc);
        synchronized (this) {
            if (current == rc) current = null;
        }
    }

    private static boolean isStopInterruption(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException || t instanceof RhinoScriptEngine.ScriptStopException) {
                return true;
            }
        }
        return false;
    }

    public void pause(long taskId) {
        RunContext rc = current;
        if (rc != null && rc.taskId == taskId) {
            rc.paused = true;
        }
    }

    public void stop(long taskId) {
        RunContext rc = current;
        if (rc != null && rc.taskId == taskId) {
            rc.stopRequested = true;
            if (rc.thread != null) rc.thread.interrupt();
        }
    }

    public void restart(JSONObject data) {
        long taskId = data.optLong("taskId", 0);
        stop(taskId);
        waitIdle(5000);
        // CMD_RESTART 只携带 taskId，复用设备缓存的最近一次 CMD_START 完整参数
        JSONObject startData = data.has("scriptId") ? data : cachedStartData(taskId);
        if (startData == null) {
            sendResult(buildResult(taskId, "FAILED", 0, 0,
                    "无法重启: 设备未缓存该任务的脚本信息", 0));
            return;
        }
        start(startData);
    }

    private JSONObject cachedStartData(long taskId) {
        synchronized (lastStartData) {
            return lastStartData.get(taskId);
        }
    }

    private void waitIdle(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (current != null && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    // 心跳 30s 一次，不能被 start 的 join 阻塞导致服务器误判离线
    public JSONObject snapshot() {
        RunContext rc = current;
        JSONObject o = new JSONObject();
        try {
            o.put("battery", batteryPercent());
            if (rc != null) {
                o.put("taskId", rc.taskId);
                o.put("running", true);
                o.put("successCount", rc.auto.getReport().getOk());
                o.put("failCount", rc.auto.getReport().getFail());
            } else {
                o.put("running", false);
            }
        } catch (Exception ignored) {
        }
        return o;
    }

    private int batteryPercent() {
        try {
            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            if (bm == null) return -1;
            int pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            return (pct >= 0 && pct <= 100) ? pct : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    public void onConnected() {
        synchronized (pendingResults) {
            while (!pendingResults.isEmpty()) {
                JSONObject result = pendingResults.poll();
                Reporter r = reporter;
                if (r != null) r.send("RESULT", result);
            }
        }
    }

    private JSONObject buildResult(long taskId, String status, int ok, int fail,
                                   String error, double duration) {
        JSONObject o = new JSONObject();
        try {
            o.put("taskId", taskId);
            o.put("status", status);
            o.put("successCount", ok);
            o.put("failCount", fail);
            o.put("duration", duration);
            if (error != null) o.put("errorMsg", error);
        } catch (Exception ignored) {
        }
        return o;
    }

    private void sendResult(JSONObject result) {
        Reporter r = reporter;
        if (r != null && r.isConnected() && r.send("RESULT", result)) {
            return;
        }
        // 断线/发送失败时缓存，重连后由 onConnected 补发
        synchronized (pendingResults) {
            if (pendingResults.size() < 100) pendingResults.add(result);
        }
    }

    public void sendLog(long taskId, String level, String tag, String content) {
        Reporter r = reporter;
        if (r == null) return;
        JSONObject o = new JSONObject();
        try {
            o.put("taskId", taskId);
            o.put("level", level);
            o.put("tag", tag);
            o.put("content", content);
            o.put("logTime", System.currentTimeMillis());
        } catch (Exception ignored) {
            return;
        }
        if (r.isConnected()) r.send("LOG", o);
    }

    public void showToast(String message) {
        mainHandler.post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }

    /** 仅发停止信号不 join，可在主线程安全调用；线程靠观察器/中断自行退出。 */
    public void requestStopAll() {
        RunContext rc = current;
        if (rc != null) {
            rc.stopRequested = true;
            if (rc.thread != null) rc.thread.interrupt();
            stopWatchdog(rc);
        }
    }

    public void shutdown() {
        requestStopAll();
    }
}
