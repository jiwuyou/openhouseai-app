package com.termux.app.smallphone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SmallPhoneRuntimeDynamicEndpointTest {

    @Test
    public void healthPathUsesPublishedDynamicEndpoint() {
        assertEquals(
            "http://127.0.0.1:24000/health",
            SmallPhoneRuntime.appendPath("http://127.0.0.1:24000/", "health")
        );
        assertEquals(
            "http://127.0.0.1:24001/",
            SmallPhoneRuntime.appendPath("http://127.0.0.1:24001", "/")
        );
    }

    @Test
    public void healthyStatusUsesDynamicUrlsOnly() {
        SmallPhoneRuntime.Status status = new SmallPhoneRuntime.Status(
            SmallPhoneRuntime.Endpoint.reachable("service-manager", "http://127.0.0.1:20087/api/v1/health", 200),
            SmallPhoneRuntime.Endpoint.reachable("SmallPhone", "http://127.0.0.1:24001/", 200),
            SmallPhoneRuntime.Endpoint.reachable("SmallPhone core", "http://127.0.0.1:24000/health", 200),
            SmallPhoneRuntime.Endpoint.disabled("cc-connect", ""),
            true
        );

        assertTrue(status.isHealthy());
        assertEquals("http://127.0.0.1:24001/", status.smallPhone.url);
        assertEquals("http://127.0.0.1:24000/health", status.smallPhoneCore.url);
        assertFalse(status.smallPhone.url.contains("22082"));
        assertFalse(status.smallPhoneCore.url.contains("22000"));
    }
}
