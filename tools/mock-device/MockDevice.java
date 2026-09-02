import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Mock Android device for end-to-end testing. JDK 11+, no external deps.
 * Usage: java MockDevice <wsBaseUrl> <deviceSn> <secret>
 */
public class MockDevice {
    static String baseUrl;
    static String sn;
    static String secret;
    static WebSocket ws;
    static final CountDownLatch QUIT = new CountDownLatch(1);
    static final StringBuilder buf = new StringBuilder();
    static String downloadedScript = "none";

    public static void main(String[] args) throws Exception {
        baseUrl = args[0];
        sn = args[1];
        secret = args[2];
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        ws = http.newWebSocketBuilder()
                .header("X-Device-Id", sn)
                .header("X-Device-Secret", secret)
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create(baseUrl.replaceFirst("^http", "ws") + "/ws/device"),
                        new Listener(http))
                .join();
        System.out.println("[mock] connected");

        send("REGISTER", """
                {"deviceName":"MockDevice-1","model":"Pixel Mock","brand":"google",
                 "androidVersion":"14","sdkInt":34,"appVersion":"1.0.0","engineVersion":"rhino-1.7.14",
                 "installedVersions":[]}""");

        Thread hb = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(15000);
                    send("HEARTBEAT", "{\"running\":false}");
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        hb.setDaemon(true);
        hb.start();

        QUIT.await();
        System.out.println("[mock] bye");
    }

    static class Listener implements WebSocket.Listener {
        final HttpClient http;

        Listener(HttpClient http) {
            this.http = http;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String msg = buf.toString();
                buf.setLength(0);
                try {
                    handleMessage(msg);
                } catch (Exception e) {
                    System.out.println("[mock] handle error: " + e);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            System.out.println("[mock] closed: " + statusCode + " " + reason);
            QUIT.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            System.out.println("[mock] ws error: " + error.getMessage());
            QUIT.countDown();
        }

        void handleMessage(String raw) throws Exception {
            // minimal JSON field extraction (no deps)
            String type = extract(raw, "type");
            String msgId = extract(raw, "msgId");
            switch (type) {
                case "CMD_UPDATE_SCRIPT" -> {
                    String url = extract(raw, "url");
                    String md5 = extract(raw, "md5");
                    String actual = downloadAndHash(url);
                    boolean ok = md5.equalsIgnoreCase(actual);
                    System.out.println("[mock] UPDATE_SCRIPT url=" + url + " md5Ok=" + ok);
                    downloadedScript = "v" + extract(raw, "versionCode");
                    ack(msgId, ok, ok ? null : "md5 mismatch");
                }
                case "CMD_START" -> {
                    System.out.println("[mock] CMD_START received, simulating a run...");
                    ack(msgId, true, null);
                    simulateRun(extract(raw, "taskId"));
                }
                case "CMD_PAUSE" -> { System.out.println("[mock] CMD_PAUSE"); ack(msgId, true, null); }
                case "CMD_STOP" -> { System.out.println("[mock] CMD_STOP"); ack(msgId, true, null); }
                case "CMD_RESTART" -> { System.out.println("[mock] CMD_RESTART"); ack(msgId, true, null); }
                case "REGISTER_ACK" -> System.out.println("[mock] registered on server ✓");
                default -> System.out.println("[mock] msg " + type);
            }
        }

        void simulateRun(String taskId) {
            CompletableFuture.runAsync(() -> {
                try {
                    send("RESULT", "{\"taskId\":" + taskId + ",\"status\":\"RUNNING\",\"successCount\":0,\"failCount\":0}");
                    for (int i = 1; i <= 3; i++) {
                        Thread.sleep(300);
                        send("LOG", "{\"taskId\":" + taskId + ",\"level\":\"INFO\",\"tag\":\"script\",\"content\":\"step " + i + " done, script=" + downloadedScript + "\"}");
                        send("RESULT", "{\"taskId\":" + taskId + ",\"status\":\"RUNNING\",\"successCount\":" + i + ",\"failCount\":0,\"duration\":" + (i * 0.3) + "}");
                    }
                    Thread.sleep(300);
                    send("RESULT", "{\"taskId\":" + taskId + ",\"status\":\"SUCCESS\",\"successCount\":3,\"failCount\":1,\"duration\":1.2}");
                    System.out.println("[mock] run finished, RESULT SUCCESS sent ✓");
                } catch (InterruptedException ignored) {
                }
            });
        }

        String downloadAndHash(String path) throws Exception {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .header("X-Device-Sn", sn)
                    .header("X-Device-Secret", secret)
                    .GET().build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                // 避免对错误页算 md5 误报 mismatch
                throw new IllegalStateException("download failed HTTP " + resp.statusCode() + " for " + path);
            }
            byte[] digest = MessageDigest.getInstance("MD5").digest(resp.body());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        }
    }

    static void ack(String msgId, boolean ok, String error) {
        String err = error == null ? "" : ",\"error\":\"" + error + "\"";
        send("ACK", "{\"refMsgId\":\"" + msgId + "\",\"ok\":" + ok + err + "}");
    }

    static void send(String type, String dataJson) {
        String json = "{\"type\":\"" + type + "\",\"msgId\":\"" + UUID.randomUUID()
                + "\",\"ts\":" + System.currentTimeMillis() + ",\"data\":" + dataJson + "}";
        ws.sendText(json, true);
    }

    static String extract(String json, String key) {
        int i = json.indexOf("\"" + key + "\":");
        if (i < 0) return "";
        i += key.length() + 3;
        while (i < json.length() && json.charAt(i) == ' ') i++;
        if (i >= json.length()) return "";
        if (json.charAt(i) == '"') {
            int end = json.indexOf('"', i + 1);
            if (end < 0) return json.substring(i + 1); // 串尾缺右引号，取剩余
            return json.substring(i + 1, end);
        }
        int end = i;
        while (end < json.length() && ",}]".indexOf(json.charAt(end)) < 0) end++;
        return json.substring(i, end);
    }
}
