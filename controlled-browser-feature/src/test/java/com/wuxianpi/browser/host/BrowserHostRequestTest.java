package com.wuxianpi.browser.host;

import android.os.Bundle;

import com.termux.app.browser.ControlledBrowserContract;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

@RunWith(RobolectricTestRunner.class)
public class BrowserHostRequestTest {
    @Test public void legacyOpenRoundTripPreservesUrlAndRequestId() {
        Bundle command = new Bundle();
        command.putString(ControlledBrowserContract.EXTRA_COMMAND, ControlledBrowserContract.COMMAND_OPEN);
        command.putString(ControlledBrowserContract.EXTRA_REQUEST_ID, "legacy-1");
        command.putString(ControlledBrowserContract.EXTRA_URL, "https://example.com/");

        BrowserHostRequest request = BrowserHostRequest.fromLegacyBundle(command);
        Bundle roundTrip = request.toLegacyBundle();

        assertEquals(BrowserHostContract.PAGE_NAVIGATE, request.method);
        assertEquals("legacy-1", request.requestId);
        assertEquals("https://example.com/", roundTrip.getString(ControlledBrowserContract.EXTRA_URL));
    }

    @Test public void websocketEnvelopeAcceptsNestedRequestAndTarget() throws Exception {
        JSONObject envelope = new JSONObject("{\"type\":\"browser.invoke\",\"request\":{"
            + "\"id\":\"r2\",\"method\":\"app.invoke\","
            + "\"target\":{\"hostId\":\"native-browser\",\"tabId\":\"t1\"},"
            + "\"params\":{\"action\":\"save\",\"args\":{\"value\":1}}}}" );

        BrowserHostRequest request = BrowserHostRequest.fromJson(envelope);

        assertEquals("r2", request.requestId);
        assertEquals("native-browser", request.hostId);
        assertEquals("t1", request.tabId);
        assertEquals("save", request.params.getString("action"));
    }

    @Test public void runtimeV1InvokeAndResultUseIdField() throws Exception {
        JSONObject invoke = new JSONObject("{\"type\":\"browser.invoke\","
            + "\"protocol\":\"wuxianpi-browser-host-v1\",\"protocolVersion\":1,"
            + "\"id\":\"runtime-1\",\"method\":\"page.getText\","
            + "\"target\":{\"hostId\":\"native-browser\",\"tabId\":\"tab-1\"},"
            + "\"params\":{\"visibleOnly\":true}}" );
        BrowserHostRequest request = BrowserHostRequest.fromJson(invoke);
        JSONObject result = new BrowserHostResponse(
            request.requestId, true, new JSONObject().put("text", "ok"), null).toJson();

        assertEquals("runtime-1", request.requestId);
        assertEquals("native-browser", request.hostId);
        assertEquals("tab-1", request.tabId);
        assertEquals("runtime-1", result.getString("id"));
        assertFalse(result.has("requestId"));
        assertFalse(result.has("protocolVersion"));
    }
}
