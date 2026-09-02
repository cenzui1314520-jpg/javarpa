package com.rpa.engine.api;

import android.view.accessibility.AccessibilityNodeInfo;

import com.rpa.engine.accessibility.UiOperator;

import java.util.ArrayList;
import java.util.List;

/** JS-facing selector builder: auto.text("xx").findOne(5000). */
public class SelectorApi {
    private String text;
    private String textContains;
    private String idSuffix;
    private String desc;
    private String className;
    private Boolean clickable;

    public SelectorApi text(String text) {
        this.text = text;
        return this;
    }

    public SelectorApi textContains(String s) {
        this.textContains = s;
        return this;
    }

    public SelectorApi id(String idSuffix) {
        this.idSuffix = idSuffix;
        return this;
    }

    public SelectorApi desc(String desc) {
        this.desc = desc;
        return this;
    }

    public SelectorApi type(String className) {
        this.className = className;
        return this;
    }

    public SelectorApi clickable(boolean clickable) {
        this.clickable = clickable;
        return this;
    }

    private UiOperator.Criteria criteria() {
        UiOperator.Criteria c = new UiOperator.Criteria();
        c.text = text;
        c.textContains = textContains;
        c.idSuffix = idSuffix;
        c.desc = desc;
        c.className = className;
        c.clickable = clickable;
        return c;
    }

    /** Poll until timeout(ms). Returns NodeApi or null. */
    public NodeApi findOne(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        do {
            AccessibilityNodeInfo node = UiOperator.findFirst(criteria());
            if (node != null) return new NodeApi(node);
            if (timeoutMs <= 0) break;
            Thread.sleep(Math.min(200, timeoutMs));
        } while (System.currentTimeMillis() < deadline);
        return null;
    }

    public NodeApi find() throws InterruptedException {
        return findOne(0);
    }

    public boolean exists() throws InterruptedException {
        return findOne(0) != null;
    }

    public List<NodeApi> findAll() {
        List<NodeApi> result = new ArrayList<>();
        for (AccessibilityNodeInfo n : UiOperator.findAll(criteria())) {
            result.add(new NodeApi(n));
        }
        return result;
    }
}
