package com.rpa.engine.task;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.rpa.engine.api.AutoApi;
import com.rpa.engine.engine.RhinoScriptEngine;
import com.rpa.engine.script.ScriptRepository;
import com.rpa.engine.util.Prefs;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Queue;

/** Runs one cloud task at a time in a dedicated thread; reports status via WS. */
public class TaskExecutor {

    public interface Reporter {
        void send(String type, JSONObject data);
        boolean isConnected();
    }

    private final Context context;
    private final ScriptRepository repository;
    private final RhinoScriptEngine engine = new RhinoScriptEngine();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Queue<JSONObject> pendingResults = new ArrayDeque<>();

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
        final long startedAt = System.currentTimeMillis();
        final AutoApi auto = new AutoApi(this);
        volatile Thread thread;
        volatile boolean paused;
        volatile boolean stopRequested;

        RunContext(JSONObject data) throws Exception {
            this.taskId = data.getLong("taskId");
            this.scriptId = data.getLong("scriptId");
            this.versionCode = data.getInt("versionCode");
            this.paramsJson = data.optString("params", "{}");
            this.url = data.optString("url", "");
            this.md5 = data.optString("md5", null);
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

    public synchronized void start(JSONObject data) {
        RunContext rc;
        try {
            rc = new RunContext(data);
        } catch (Exception e) {
            sendResult(buildResult(data.optLong("taskId", 0), "FAILED",
                    0, 0, "指令参数不完整: " + e.getMessage(), 0));
            return;
        }
        stopCurrent(3000);
        current = rc;
        rc.thread = new Thread(() -> runScript(rc), "rpa-task-" + rc.taskId);
        rc.thread.start();
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
        } catch (Exception e) {
            status = "FAILED";
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        double duration = (System.currentTimeMillis() - rc.startedAt) / 1000.0;
        int ok = rc.auto.getReport().getOk();
        int fail = rc.auto.getReport().getFail();
        sendResult(buildResult(rc.taskId, status, ok, fail, error, duration));
        if (current == rc) current = null;
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
        new Thread(() -> {
            stop(taskId);
            waitIdle(5000);
            start(data);
        }, "rpa-restart").start();
    }

    private synchronized void stopCurrent(long timeoutMs) {
        RunContext rc = current;
        if (rc != null) {
            rc.stopRequested = true;
            if (rc.thread != null) {
                rc.thread.interrupt();
                try {
                    rc.thread.join(timeoutMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            current = null;
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

    public synchronized JSONObject snapshot() {
        RunContext rc = current;
        JSONObject o = new JSONObject();
        try {
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
        if (r != null && r.isConnected()) {
            r.send("RESULT", result);
        } else {
            synchronized (pendingResults) {
                if (pendingResults.size() < 100) pendingResults.add(result);
            }
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

    public void shutdown() {
        stopCurrent(2000);
    }
}
