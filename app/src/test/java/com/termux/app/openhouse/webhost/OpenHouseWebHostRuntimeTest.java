package com.termux.app.openhouse.webhost;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OpenHouseWebHostRuntimeTest {

    private static final String TICKET = "abcdefghijklmnopqrstuvwxyzABCDEFG_123456789-xyz";

    @Test
    public void webViewAllowlistAcceptsOnlyHttpLoopback() {
        assertTrue(OpenHouseWebHostRuntime.isAllowedLoopbackUrl("http://127.0.0.1:22110/"));
        assertTrue(OpenHouseWebHostRuntime.isAllowedLoopbackUrl("http://localhost:20087/web-session"));
        assertTrue(OpenHouseWebHostRuntime.isAllowedLoopbackUrl("http://[::1]:30141/"));
        assertFalse(OpenHouseWebHostRuntime.isAllowedLoopbackUrl("https://127.0.0.1:22110/"));
        assertFalse(OpenHouseWebHostRuntime.isAllowedLoopbackUrl("http://example.com/"));
        assertFalse(OpenHouseWebHostRuntime.isAllowedLoopbackUrl("file:///data/data/com.termux/files/home/token"));
        assertFalse(OpenHouseWebHostRuntime.isAllowedLoopbackUrl("javascript:alert(1)"));
        assertFalse(OpenHouseWebHostRuntime.isAllowedLoopbackUrl("http://user@127.0.0.1:22110/"));
        assertFalse(OpenHouseWebHostRuntime.isAllowedLoopbackUrl("http://127.0.0.1@evil.example/"));
        assertFalse(OpenHouseWebHostRuntime.isAllowedLoopbackUrl("http://127.0.0.1.evil.example/"));
    }

    @Test
    public void finalRenderGateRejectsNonLoopbackAndRecoveryTargets() {
        assertTrue(OpenHouseWebHostRuntime.isSafeWebViewTarget(
            OpenHouseWebHostRuntime.Target.OPENHOUSE_WEB,
            "http://127.0.0.1:22110/"
        ));
        assertTrue(OpenHouseWebHostRuntime.isSafeWebViewTarget(
            OpenHouseWebHostRuntime.Target.SERVICE_MANAGER,
            "http://127.0.0.1:20087/web-session?ticket=" + TICKET
        ));
        assertFalse(OpenHouseWebHostRuntime.isSafeWebViewTarget(
            OpenHouseWebHostRuntime.Target.OPENHOUSE_WEB,
            "http://evil.example/"
        ));
        assertFalse(OpenHouseWebHostRuntime.isSafeWebViewTarget(
            OpenHouseWebHostRuntime.Target.SERVICE_MANAGER,
            "https://127.0.0.1:20087/web-session?ticket=" + TICKET
        ));
        assertFalse(OpenHouseWebHostRuntime.isSafeWebViewTarget(
            OpenHouseWebHostRuntime.Target.SERVICE_MANAGER,
            "http://user@127.0.0.1:20087/web-session?ticket=" + TICKET
        ));
        assertFalse(OpenHouseWebHostRuntime.isSafeWebViewTarget(
            OpenHouseWebHostRuntime.Target.NATIVE_RECOVERY,
            "http://127.0.0.1:20087/"
        ));
    }

    @Test
    public void popupWindowsAreAlwaysDisabled() {
        assertFalse(OpenHouseWebHostRuntime.arePopupWindowsAllowed());
    }

    @Test
    public void automaticWebHostRecoveryOnlyOpensNativeRecovery() {
        assertTrue(OpenHouseWebHostRuntime.shouldOpenNativeRecovery(false));
        assertFalse(OpenHouseWebHostRuntime.shouldOpenNativeRecovery(true));
        assertTrue(
            OpenHouseWebHostRuntime.automaticRecoveryAction()
                == OpenHouseWebHostRuntime.AutomaticRecoveryAction.NATIVE_RECOVERY_ONLY
        );
    }

    @Test
    public void healthyLegacyManagerDoesNotTriggerAutomaticUpgrade() {
        boolean legacyManagerHealthSucceeded = true;
        assertFalse(OpenHouseWebHostRuntime.shouldOpenNativeRecovery(legacyManagerHealthSucceeded));
    }

    @Test
    public void oneTimeTicketsAndServiceManagerPathsAreStrictlyValidated() {
        assertTrue(OpenHouseWebHostRuntime.isSafeOneTimeTicket(TICKET));
        assertTrue(OpenHouseWebHostRuntime.isSafeServiceManagerSessionPath("/web-session?ticket=" + TICKET));
        assertFalse(OpenHouseWebHostRuntime.isSafeOneTimeTicket("short"));
        assertFalse(OpenHouseWebHostRuntime.isSafeOneTimeTicket(TICKET + "&next=http://evil.example"));
        assertFalse(OpenHouseWebHostRuntime.isSafeServiceManagerSessionPath("/?token=" + TICKET));
        assertFalse(OpenHouseWebHostRuntime.isSafeServiceManagerSessionPath("http://evil.example/web-session?ticket=" + TICKET));
    }
}
