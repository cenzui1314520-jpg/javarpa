package com.rpa.engine.ws;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.rpa.engine.accessibility.AutoAccessibilityService;
import com.rpa.engine.accessibility.UiOperator;
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
    // 调试截图上行规格：720 宽 JPEG 已够人看/定位控件，同时把 base64 控制在 ~100KB 量级
    private static final int CAPTURE_MAX_WIDTH = 720;
    private static final int CAPTURE_QUALITY = 60;

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
    // 脚本包下载安装线程：有界单线程，替代每条命令裸 new Thread
    private final ExecutorService installExecutor = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "rpa-install"));
    // 调试指令线程：UI 树 dump/截图压缩耗时几十到几百 ms，独立于启停调度避免互相阻塞
    private final ExecutorService debugExecutor = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "rpa-debug"));

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
            // HttpUrl 不支持 ws:// scheme（parse 一律返回 null），合法性校验用原始 http 地址；
            // ws→http 的转换由 Request.Builder.url(String) 内部完成
            if (HttpUrl.parse(server.replaceAll("/+$", "")) == null) {
                // 地址配置错误重连无意义，直接终止并提示用户修正
                status("服务器地址非法，请检查设置: " + server);
                return;
            }
            Request request = new Request.Builder()
                    .url(wsUrl)
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
            // 旧连接的迟到回调不得处理，否则会误清新连接的状态/触发多余重连
            if (webSocket != socket) return;
            reconnectAttempts = 0;
            connected = true;
            status("已连接");
            sendRegister();
            taskExecutor.onConnected();
            startHeartbeat();
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            // 与 onOpen/onFailure/onClosed 一致：旧连接的迟到消息不得处理，避免重连竞态下重复执行指令
            if (webSocket != socket) return;
            handleMessage(text);
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            if (webSocket != socket) return;
            connected = false;
            socket = null;
            stopHeartbeat();
            // 握手期 HTTP 401/403：凭据错误重连永远不可能成功，明确提示后停止
            if (response != null && (response.code() == 401 || response.code() == 403)) {
                status("鉴权失败(HTTP " + response.code()
                        + ")，请核对设备编号/密钥，或在管理后台重置密钥后重新扫码");
                return;
            }
            status("连接断开: " + t.getMessage());
            scheduleReconnect();
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            if (webSocket != socket) return;
            connected = false;
            socket = null;
            stopHeartbeat();
            if (code == 4001) {
                // 协议约定：鉴权失败服务端以 4001 关闭，重连永远不可能成功
                status("鉴权失败，请检查设备编号/密钥");
                return;
            }
            status("连接关闭");
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
                    // 启停指令含旧任务 join（最长 3s），放调度线程避免阻塞 WS 读循环；
                    // ACK 在派发执行后按真实受理结果回填，而非收到即回 ok
                    dispatchExecutor.execute(() -> ack(msgId, taskExecutor.start(payload)));
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
                    // restart 含 waitIdle(5s)，与 start 一样走调度线程串行执行
                    dispatchExecutor.execute(() -> ack(msgId, taskExecutor.restart(payload)));
                    break;
                case "CMD_UPDATE_SCRIPT":
                    installScript(msgId, data);
                    break;
                case "CMD_DUMP_UI":
                    debugExecutor.execute(() -> handleDumpUi(msgId));
                    break;
                case "CMD_CAPTURE":
                    debugExecutor.execute(() -> handleCapture(msgId));
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            android.util.Log.w("WsClient", "handle message failed: " + text, e);
        }
    }

    private void installScript(String msgId, JSONObject data) {
        installExecutor.execute(() -> {
            String error = null;
            try {
                repository.ensureInstalled(
                        data.getLong("scriptId"),
                        data.getInt("versionCode"),
                        data.optString("url", ""),
                        data.optString("sha256", null),
                        Prefs.serverUrl(context),
                        Prefs.deviceSn(context),
                        Prefs.secret(context));
            } catch (Exception e) {
                error = e.getMessage();
            }
            ack(msgId, error == null, error);
        });
    }

    /** 调试指令：dump 控件树后上行 DUMP_UI，ACK 按真实结果回填。 */
    private void handleDumpUi(String msgId) {
        try {
            if (!AutoAccessibilityService.isRunning()) {
                ack(msgId, false, "无障碍服务未开启");
                return;
            }
            JSONObject data = new JSONObject();
            data.put("refMsgId", msgId);
            data.put("tree", UiOperator.dumpTree());
            send("DUMP_UI", data);
            ack(msgId, true, null);
        } catch (Exception e) {
            ack(msgId, false, e.getMessage());
        }
    }

    /** 调试指令：截图压缩后上行 CAPTURE，失败（如 Android<11）时 ACK 带 reason。 */
    private void handleCapture(String msgId) {
        try {
            JSONObject cap = UiOperator.captureJpeg(CAPTURE_MAX_WIDTH, CAPTURE_QUALITY);
            if (cap == null) {
                ack(msgId, false, "截图失败（需 Android 11+ 且无障碍服务运行中）");
                return;
            }
            cap.put("refMsgId", msgId);
            send("CAPTURE", cap);
            ack(msgId, true, null);
        } catch (Exception e) {
            ack(msgId, false, e.getMessage());
        }
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
        } catch (Exception e) {
            // 注册失败设备将一直是"已连接的幽灵连接"，必须可见并重连
            android.util.Log.w("WsClient", "sendRegister failed", e);
            status("注册失败: " + e.getMessage());
            WebSocket s = socket;
            if (s != null) s.close(4001, "register failed");
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

    /** error 为 null 视为成功；非 null 时 ACK 失败原因。 */
    private void ack(String refMsgId, String error) {
        ack(refMsgId, error == null, error);
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
        installExecutor.shutdownNow();
        debugExecutor.shutdownNow();
        WebSocket s = socket;
        if (s != null) {
            s.close(1000, "bye");
            socket = null;
        }
        mainHandler.removeCallbacksAndMessages(null);
        client.dispatcher().executorService().shutdown();
    }
}
