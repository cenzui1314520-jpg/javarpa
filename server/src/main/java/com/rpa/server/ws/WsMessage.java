package com.rpa.server.ws;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class WsMessage {
    public String type;
    public String msgId;
    public long ts;
    public Map<String, Object> data = new HashMap<>();

    public static WsMessage of(String type, Map<String, Object> data) {
        WsMessage m = new WsMessage();
        m.type = type;
        m.msgId = UUID.randomUUID().toString();
        m.ts = System.currentTimeMillis();
        if (data != null) m.data = data;
        return m;
    }
}
