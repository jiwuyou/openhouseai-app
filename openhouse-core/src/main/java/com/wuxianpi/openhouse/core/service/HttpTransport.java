package com.wuxianpi.openhouse.core.service;

import com.wuxianpi.openhouse.core.RuntimeConnection;

import java.io.IOException;

public interface HttpTransport {
    HttpResponseSpec execute(RuntimeConnection connection, HttpRequestSpec request) throws IOException;
}
