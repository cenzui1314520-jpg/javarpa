package com.rpa.engine.api;

import java.util.concurrent.atomic.AtomicInteger;

/** Success/fail counters reported to cloud via RESULT/HEARTBEAT. */
public class ReportApi {
    // 脚本线程写、心跳线程读，需保证可见性与原子性
    private final AtomicInteger ok = new AtomicInteger();
    private final AtomicInteger fail = new AtomicInteger();

    public void ok() {
        ok.incrementAndGet();
    }

    public void fail() {
        fail.incrementAndGet();
    }

    public void okN(int n) {
        ok.addAndGet(n);
    }

    public void failN(int n) {
        fail.addAndGet(n);
    }

    public int getOk() {
        return ok.get();
    }

    public int getFail() {
        return fail.get();
    }
}
