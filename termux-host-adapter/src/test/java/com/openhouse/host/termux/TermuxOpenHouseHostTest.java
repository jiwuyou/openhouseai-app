package com.openhouse.host.termux;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TermuxOpenHouseHostTest {
    @Test
    public void normalizesCanonicalServiceManagerBind() {
        assertEquals("http://127.0.0.1:20087",
            TermuxOpenHouseHost.normalizeServiceManagerUrl("0.0.0.0:20087"));
        assertEquals("http://127.0.0.1:21000",
            TermuxOpenHouseHost.normalizeServiceManagerUrl(":21000"));
    }
}
