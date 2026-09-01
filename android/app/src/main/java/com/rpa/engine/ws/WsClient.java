package com.rpa.engine.ws;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.rpa.engine.script.ScriptRepository;
import com.rpa.engine.task.TaskExecutor;
import com.rpa.engine.util.Prefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.function.Consumer;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Device-side WS client: register, heartbeat, command dispatch, auto reconnect. */
public class WsClient implements TaskExecutor.Reporter {
    private static final int HEARTBEAT_MS = 30_000;

    private final Context context;
    private final TaskExecutor taskExecutor;
    private final ScriptRepository repository;
    private final Consumer<String> statusListener;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .pingInterval(25, java.util.concurrent.TimeUnit.SECONDS)
            .build();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private volatile WebSocket socket;
    private Timer heartbeatTimer;
    private volatile boolean closed;
    private volatile int reconnectAttempts = 0;

    public WsClient(Context context, TaskExecutor taskExecutor, ScriptRepository repository,
                    Consumer<String> statusListener) {
        this.context = context;
        this.taskExecutor = taskExecutor;
        this.repository = repository;
        this.statusListener = statusListener;
    }

    public void start() {
        closed = false;
        connect();
    }

    private void connect() {
        if (closed) return;
        String server = Prefs.serverUrl(context);
        String wsUrl = server.replaceFirst("^http", "ws").replaceAll("/+$", "") + "/ws/device";
        Request request = new Request.Builder()
                .url(wsUrl)
                .header("X-Device-Id", Prefs.deviceSn(context))
                .header("X-Device-Secret", Prefs.secret(context))
                .build();
        status("连接中...");
        socket = client.newWebSocket(request, listener);
    }

    private final WebSocketListener listener = new WebSocketListener() {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            reconnectAttempts = 0;
            status("已连接");
            sendRegister();
            taskExecutor.onConnected();
            startHeartbeat();
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            handleMessage(text);
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            status("连接断开: " + t.getMessage());
            stopHeartbeat();
            scheduleReconnect();
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            status("连接关闭");
            stopHeartbeat();
            scheduleReconnect();
        }
    };

    private void handleMessage(String text) {
        try {
            JSONObject msg = new JSONObject(text);
            String type = msg.optString("type");
            String msgId = msg.optString("msgId");
            JSONObject data = msg.optJSONObject("data");
            if (data == null) data = new JSONObject();
            switch (type) {
                case "CMD_START":
                    taskExecutor.start(data);
                    ack(msgId, true, null);
                    break;
                case "CMD_PAUSE":
                    taskExecutor.pause(data.optLong("taskId"));
                    ack(msgId, true, null);
                    break;
                case "CMD_STOP":
                    taskExecutor.stop(data.optLong("taskId"));
                    ack(msgId, true, null);
                    break;
                case "CMD_RESTART":
                    taskExecutor.restart(data);
                    ack(msgId, true, null);
                    break;
                case "CMD_UPDATE_SCRIPT":
                    installScript(msgId, data);
                    break;
                default:
                    break;
            }
        } catch (Exception ignored) {
        }
    }

    private void installScript(String msgId, JSONObject data) {
        new Thread(() -> {
            String error = null;
            try {
                repository.ensureInstalled(
                        data.getLong("scriptId"),
                        data.getInt("versionCode"),
                        data.optString("url", ""),
                        data.optString("md5", null),
                        Prefs.serverUrl(context),
                        Prefs.deviceSn(context),
                        Prefs.secret(context));
            } catch (Exception e) {
                error = e.getMessage();
            }
            ack(msgId, error == null, error);
        }, "rpa-install").start();
    }

    private void sendRegister() {
        try {
            JSONObject data = new JSONObject();
            data.put("deviceName", Build.MODEL + "-" + Prefs.deviceSn(context));
            data.put("model", Build.MODEL);
            data.put("brand", Build.BRAND);
            data.put("androidVersion", Build.VERSION.RELEASE);
            data.put("sdkInt", Build.VERSION.SDK_INT);
            data.put("appVersion", appVersion());
            data.put("engineVersion", "rhino-1.7.14");
            JSONArray installed = new JSONArray();
            for (JSONObject o : repository.installedVersions()) {
                installed.put(o);
            }
            data.put("installedVersions", installed);
            send("REGISTER", data);
        } catch (Exception ignored) {
        }
    }

    private String appVersion() {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatTimer = new Timer("rpa-heartbeat");
        heartbeatTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                send("HEARTBEAT", taskExecutor.snapshot());
            }
        }, HEARTBEAT_MS, HEARTBEAT_MS);
    }

    private void stopHeartbeat() {
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel();
            heartbeatTimer = null;
        }
    }

    private void scheduleReconnect() {
        if (closed) return;
        reconnectAttempts++;
        long delay = Math.min(60_000, 5_000L * (1L << Math.min(reconnectAttempts, 4)));
        delay += random.nextInt(3000);
        mainHandler.postDelayed(this::connect, delay);
        status("将在 " + (delay / 1000) + "s 后重连");
    }

    @Override
    public boolean isConnected() {
        WebSocket s = socket;
        return s != null;
    }

    @Override
    public void send(String type, JSONObject data) {
        WebSocket s = socket;
        if (s == null) return;
        try {
            JSONObject envelope = new JSONObject();
            envelope.put("type", type);
            envelope.put("msgId", java.util.UUID.randomUUID().toString());
            envelope.put("ts", System.currentTimeMillis());
            envelope.put("data", data);
            s.send(envelope.toString());
        } catch (Exception ignored) {
        }
    }

    private void ack(String refMsgId, boolean ok, String error) {
        try {
            JSONObject data = new JSONObject();
            data.put("refMsgId", refMsgId);
            data.put("ok", ok);
            if (error != null) data.put("error", error);
            send("ACK", data);
        } catch (Exception ignored) {
        }
    }

    private void status(String s) {
        Consumer<String> l = statusListener;
        if (l != null) {
            mainHandler.post(() -> l.accept(s));
        }
    }

    public void close() {
        closed = true;
        stopHeartbeat();
        WebSocket s = socket;
        if (s != null) {
            s.close(1000, "bye");
            socket = null;
        }
        mainHandler.removeCallbacksAndMessages(null);
        client.dispatcher().executorService().shutdown();
    }
}
