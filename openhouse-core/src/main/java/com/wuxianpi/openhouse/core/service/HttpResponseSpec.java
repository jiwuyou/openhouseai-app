package com.wuxianpi.openhouse.core.service;

public final class HttpResponseSpec {
    public final int code;
    public final String body;

    public HttpResponseSpec(int code, String body) {
        this.code = code;
        this.body = body == null ? "" : body;
    }

    public boolean isSuccess() { return code >= 200 && code < 300; }
}
