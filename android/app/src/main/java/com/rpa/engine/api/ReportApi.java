package com.rpa.engine.api;

/** Success/fail counters reported to cloud via RESULT/HEARTBEAT. */
public class ReportApi {
    private int ok;
    private int fail;

    public void ok() {
        ok++;
    }

    public void fail() {
        fail++;
    }

    public void okN(int n) {
        ok += n;
    }

    public void failN(int n) {
        fail += n;
    }

    public int getOk() {
        return ok;
    }

    public int getFail() {
        return fail;
    }
}
