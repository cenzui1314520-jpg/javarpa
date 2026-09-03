import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
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
                    String sha256 = extract(raw, "sha256");
                    String actual = downloadAndHash(url);
                    boolean ok = sha256.equalsIgnoreCase(actual);
                    System.out.println("[mock] UPDATE_SCRIPT url=" + url + " sha256Ok=" + ok);
                    downloadedScript = "v" + extract(raw, "versionCode");
                    ack(msgId, ok, ok ? null : "sha256 mismatch");
                }
                case "CMD_START" -> {
                    System.out.println("[mock] CMD_START received, simulating a run...");
                    ack(msgId, true, null);
                    simulateRun(extract(raw, "taskId"));
                }
                case "CMD_PAUSE" -> { System.out.println("[mock] CMD_PAUSE"); ack(msgId, true, null); }
                case "CMD_STOP" -> { System.out.println("[mock] CMD_STOP"); ack(msgId, true, null); }
                case "CMD_RESTART" -> { System.out.println("[mock] CMD_RESTART"); ack(msgId, true, null); }
                case "CMD_DUMP_UI" -> {
                    System.out.println("[mock] CMD_DUMP_UI");
                    ack(msgId, true, null);
                    send("DUMP_UI", "{\"refMsgId\":\"" + msgId + "\",\"tree\":{\"nodeCount\":4,\"roots\":["
                            + mockNode("android.widget.FrameLayout", null, "com.mock:id/root", 0, 0, 1080, 2400, false,
                                mockNode("android.widget.TextView", "Mock Title", "com.mock:id/title", 100, 200, 880, 120, false, null)
                                + "," + mockNode("android.widget.EditText", "input account", "com.mock:id/input", 100, 1000, 880, 160, false, null)
                                + "," + mockNode("android.widget.Button", "Login", "com.mock:id/btn_login", 240, 1800, 600, 180, true, null))
                            + "]}}");
                    System.out.println("[mock] DUMP_UI sent (4 nodes) ✓");
                }
                case "CMD_CAPTURE" -> {
                    System.out.println("[mock] CMD_CAPTURE");
                    ack(msgId, true, null);
                    CompletableFuture.runAsync(() -> {
                        try {
                            String b64 = mockJpeg();
                            send("CAPTURE", "{\"refMsgId\":\"" + msgId
                                    + "\",\"width\":1080,\"height\":2400,\"image\":\"" + b64 + "\"}");
                            System.out.println("[mock] CAPTURE sent, base64 " + (b64.length() / 1024) + "KB ✓");
                        } catch (Exception e) {
                            System.out.println("[mock] capture failed: " + e);
                        }
                    });
                }
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
                // 避免对错误页算 sha256 误报 mismatch
                throw new IllegalStateException("download failed HTTP " + resp.statusCode() + " for " + path);
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(resp.body());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        }
    }

    static void ack(String msgId, boolean ok, String error) {
        String err = error == null ? "" : ",\"error\":\"" + error + "\"";
        send("ACK", "{\"refMsgId\":\"" + msgId + "\",\"ok\":" + ok + err + "}");
    }

    /** mock 控件节点 JSON；childrenJson 为 null 表示叶子。 */
    static String mockNode(String cls, String text, String id, int x, int y, int w, int h,
                           boolean clickable, String childrenJson) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"className\":\"").append(cls).append('"');
        if (text != null) sb.append(",\"text\":\"").append(text).append('"');
        if (id != null) sb.append(",\"id\":\"").append(id).append('"');
        sb.append(",\"rect\":{\"x\":").append(x).append(",\"y\":").append(y)
                .append(",\"w\":").append(w).append(",\"h\":").append(h).append('}');
        sb.append(",\"clickable\":").append(clickable);
        sb.append(",\"scrollable\":false,\"enabled\":true,\"visibleToUser\":true");
        if (childrenJson != null) {
            sb.append(",\"childCount\":3,\"children\":[").append(childrenJson).append(']');
        } else {
            sb.append(",\"childCount\":0,\"children\":[]");
        }
        return sb.append('}').toString();
    }

    /** 生成 720x1600 的 mock 截图 JPEG base64（headless 绘制，与 1080x2400 屏幕等比）。 */
    static String mockJpeg() throws Exception {
        BufferedImage bi = new BufferedImage(720, 1600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bi.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 720, 1600);
        g.setColor(Color.DARK_GRAY);
        g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 44));
        g.drawString("JavaRPA MOCK SCREEN", 90, 320);
        g.setColor(Color.LIGHT_GRAY);
        g.fillRect(66, 660, 588, 110);
        g.setColor(new Color(64, 158, 255));
        g.fillRect(160, 1200, 400, 120);
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(bi, "jpg", bos);
        return Base64.getEncoder().encodeToString(bos.toByteArray());
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
