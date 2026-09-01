package com.rpa.server.common;

public class ApiException extends RuntimeException {
    private final int code;

    public ApiException(String msg) {
        this(400, msg);
    }

    public ApiException(int code, String msg) {
        super(msg);
        this.code = code;
    }

    public int getCode() { return code; }
}
