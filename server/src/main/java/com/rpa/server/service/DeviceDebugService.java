package com.rpa.server.service;

import com.rpa.server.common.ApiException;
import com.rpa.server.ws.AdminStompService;
import com.rpa.server.ws.DeviceSessionManager;
import com.rpa.server.ws.WsMessage;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备调试通道（云端 UI 检查器）：下发 CMD_DUMP_UI/CMD_CAPTURE，
 * 接收设备 DUMP_UI/CAPTURE 上行，缓存最近一份并经 STOMP 实时推送。
 */
@Service
public class DeviceDebugService {
    private static final Map<String, String> KIND_TO_CMD = Map.of(
            "dump", "CMD_DUMP_UI",
            "capture", "CMD_CAPTURE");

    private final DeviceSessionManager sessionManager;
    private final AdminStompService stomp;
    // 每设备每类型只留最近一份：key = deviceId:kind
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Map<String, Object> data, long ts) {}

    public DeviceDebugService(DeviceSessionManager sessionManager, AdminStompService stomp) {
        this.sessionManager = sessionManager;
        this.stomp = stomp;
    }

    /** 触发设备上报 UI 树/截图；调试指令不进离线队列，设备必须在线。 */
    public void request(long deviceId, String kind) {
        String cmd = KIND_TO_CMD.get(kind);
        if (cmd == null) throw new ApiException("不支持的操作: " + kind);
        if (!sessionManager.send(String.valueOf(deviceId), WsMessage.of(cmd, Map.of()))) {
            throw new ApiException(409, "设备不在线，调试指令需设备实时在线");
        }
    }

    /** 设备上行调试结果：缓存 + STOMP 推送 /topic/device/{id}/debug。 */
    public void handleUpstream(long deviceId, String kind, Map<String, Object> data) {
        if (!KIND_TO_CMD.containsKey(kind)) return;
        cache.put(deviceId + ":" + kind, new Cached(data, System.currentTimeMillis()));
        stomp.pushDeviceDebug(deviceId, kind, data);
    }

    /** 最近一次调试结果（页面初次打开/MCP 轮询用），无则 data 为 null。 */
    public Map<String, Object> latest(long deviceId, String kind) {
        if (!KIND_TO_CMD.containsKey(kind)) throw new ApiException("不支持的操作: " + kind);
        Cached c = cache.get(deviceId + ":" + kind);
        if (c == null) return null;
        return Map.of("type", kind, "ts", c.ts(), "data", c.data());
    }
}
