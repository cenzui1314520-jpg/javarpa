package com.rpa.engine.api;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import com.rpa.engine.accessibility.UiOperator;

import java.util.HashMap;
import java.util.Map;

/** JS-facing wrapper of one matched accessibility node. */
public class NodeApi {
    private final AccessibilityNodeInfo node;

    public NodeApi(AccessibilityNodeInfo node) {
        this.node = node;
    }

    public boolean click() {
        return UiOperator.clickNode(node);
    }

    public boolean input(String text) {
        return UiOperator.inputText(node, text);
    }

    public boolean scrollForward() {
        return UiOperator.scrollForward(node);
    }

    public String text() {
        CharSequence t = node.getText();
        return t == null ? null : t.toString();
    }

    public String desc() {
        CharSequence d = node.getContentDescription();
        return d == null ? null : d.toString();
    }

    public String id() {
        return node.getViewIdResourceName();
    }

    public Map<String, Integer> rect() {
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        Map<String, Integer> m = new HashMap<>();
        m.put("x", r.left);
        m.put("y", r.top);
        m.put("width", r.width());
        m.put("height", r.height());
        m.put("centerX", r.centerX());
        m.put("centerY", r.centerY());
        return m;
    }

    public boolean exists() {
        try {
            // 对象仍持有不代表节点仍有效，refresh() 才是与窗口树重新对齐的真实探测
            return node.refresh();
        } catch (Exception e) {
            return false;
        }
    }

    /** 使用完毕后释放节点（API<33 有效），长时间任务应调用避免节点池耗尽。 */
    public void close() {
        UiOperator.recycleNode(node);
    }
}
