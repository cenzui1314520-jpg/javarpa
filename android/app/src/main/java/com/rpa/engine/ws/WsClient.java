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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import okhttp3.HttpUrl;
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
    // 单线程串行处理启停类指令，避免阻塞 WS 读线程（旧任务 join 最长 3s）
    private final ExecutorService dispatchExecutor = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "rpa-dispatch"));

    private volatile WebSocket socket;
    private volatile boolean connected;
    private Timer heartbeatTimer; // guarded by this
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
        try {
            HttpUrl url = HttpUrl.parse(wsUrl);
            if (url == null) {
                throw new IllegalArgumentException("服务器地址非法: " + server);
            }
            Request request = new Request.Builder()
                    .url(url)
                    .header("X-Device-Id", Prefs.deviceSn(context))
                    .header("X-Device-Secret", Prefs.secret(context))
                    .build();
            status("连接中...");
            socket = client.newWebSocket(request, listener);
        } catch (Exception e) {
            status("连接失败: " + e.getMessage());
            scheduleReconnect();
        }
    }

    private final WebSocketListener listener = new WebSocketListener() {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            reconnectAttempts = 0;
            connected = true;
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
            connected = false;
            socket = null;
            status("连接断开: " + t.getMessage());
            stopHeartbeat();
            scheduleReconnect();
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            connected = false;
            socket = null;
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
            final JSONObject payload = data;
            switch (type) {
                case "CMD_START":
                    // 启停指令含旧任务 join（最长 3s），放调度线程避免阻塞 WS 读循环
                    dispatchExecutor.execute(() -> taskExecutor.start(payload));
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
        } catch (Exception e) {
            android.util.Log.w("WsClient", "handle message failed: " + text, e);
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

    // onOpen/onFailure/onClosed 运行在不同 OkHttp 回调线程，加锁防重复心跳
    private synchronized void startHeartbeat() {
        stopHeartbeat();
        heartbeatTimer = new Timer("rpa-heartbeat");
        heartbeatTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                send("HEARTBEAT", taskExecutor.snapshot());
            }
        }, HEARTBEAT_MS, HEARTBEAT_MS);
    }

    private synchronized void stopHeartbeat() {
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
        return connected;
    }

    @Override
    public boolean send(String type, JSONObject data) {
        WebSocket s = socket;
        if (s == null || !connected) return false;
        try {
            JSONObject envelope = new JSONObject();
            envelope.put("type", type);
            envelope.put("msgId", java.util.UUID.randomUUID().toString());
            envelope.put("ts", System.currentTimeMillis());
            envelope.put("data", data);
            return s.send(envelope.toString());
        } catch (Exception e) {
            android.util.Log.w("WsClient", "send " + type + " failed", e);
            return false;
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
        connected = false;
        stopHeartbeat();
        dispatchExecutor.shutdownNow();
        WebSocket s = socket;
        if (s != null) {
            s.close(1000, "bye");
            socket = null;
        }
        mainHandler.removeCallbacksAndMessages(null);
        client.dispatcher().executorService().shutdown();
    }
}
