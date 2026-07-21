package com.openhouse.host.nativeapp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class NativeOpenHouseHostTest {
    @Test
    public void normalizesCanonicalServiceManagerBind() {
        assertEquals("http://127.0.0.1:20087",
            NativeOpenHouseHost.normalizeServiceManagerUrl("0.0.0.0:20087"));
        assertEquals("http://127.0.0.1:21000",
            NativeOpenHouseHost.normalizeServiceManagerUrl(":21000"));
    }
}
