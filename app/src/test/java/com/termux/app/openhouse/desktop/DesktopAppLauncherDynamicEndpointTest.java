package com.termux.app.openhouse.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.app.openhouse.components.OpenHouseComponent;

import org.junit.Test;

import java.util.Arrays;

public class DesktopAppLauncherDynamicEndpointTest {

    @Test
    public void smallPhoneWebviewUsesPublishedEndpointInsteadOfStaticEntry() {
        DesktopAppLauncher launcher = new DesktopAppLauncher(
            null,
            (serviceId, endpointName) -> {
                assertEquals("smallphone-frontend-beta", serviceId);
                assertEquals("web", endpointName);
                return "http://127.0.0.1:24001/";
            }
        );
        DesktopAppDescriptor app = smallPhoneDescriptor("http://127.0.0.1:22082/");

        DesktopAppLaunchIntent intent = launcher.buildOpenIntent(app);

        assertTrue(intent.launchable);
        assertEquals(DesktopAppLaunchIntent.Kind.WEBVIEW, intent.kind);
        assertEquals("http://127.0.0.1:24001/", intent.url);
    }

    @Test
    public void smallPhoneWebviewDoesNotFallbackWhenEndpointMissing() {
        DesktopAppLauncher launcher = new DesktopAppLauncher(null, (serviceId, endpointName) -> "");

        DesktopAppLaunchIntent intent = launcher.buildOpenIntent(smallPhoneDescriptor("http://127.0.0.1:22082/"));

        assertFalse(intent.launchable);
        assertEquals(DesktopAppLaunchIntent.Kind.STATUS_PANEL, intent.kind);
        assertTrue(intent.message.contains("动态 web endpoint"));
        assertFalse(intent.url.contains("22082"));
    }

    private static DesktopAppDescriptor smallPhoneDescriptor(String staticUrl) {
        return DesktopAppDescriptor.builder()
            .id("messages")
            .title("SmallPhone")
            .entry(DesktopAppEntry.webview(staticUrl))
            .serviceNames(Arrays.asList("smallphone-frontend-beta", "smallphone-core"))
            .build();
    }
}
