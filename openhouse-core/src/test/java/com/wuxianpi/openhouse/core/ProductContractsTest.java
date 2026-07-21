package com.wuxianpi.openhouse.core;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class ProductContractsTest {
    @Test public void productRoutesRoundTripAndUnknownFallsBack() {
        for (ProductRoute route : ProductRoute.values()) {
            assertSame(route, ProductRoute.fromPersistenceKey(route.persistenceKey()));
        }
        assertSame(ProductRoute.DESKTOP, ProductRoute.fromPersistenceKey("future-route"));
        assertSame(ProductRoute.BASIC, ProductRoute.fromPersistenceKey("future-route", ProductRoute.BASIC));
        assertSame(ProductRoute.DESKTOP, ProductRoute.fromPersistenceKey("service-control"));
    }

    @Test public void capabilitiesTrimUnsupportedModesAndKeepCoreRoutes() {
        HostCapabilities capabilities = new HostCapabilities(true, false, true, false,
            false, false, true, false);
        List<ProductRoute> routes = capabilities.trimRoutes(Arrays.asList(
            ProductRoute.DESKTOP, ProductRoute.BASIC, ProductRoute.ADVANCED,
            ProductRoute.REPAIR, ProductRoute.SERVICE_CONTROL, ProductRoute.SETTINGS));
        assertEquals(Arrays.asList(ProductRoute.DESKTOP, ProductRoute.BASIC, ProductRoute.REPAIR, ProductRoute.SETTINGS), routes);
    }

    @Test public void repairAndServiceControlCannotBecomeOrdinaryDefaults() {
        assertSame(ProductRoute.DESKTOP, StartupTarget.LAST_PAGE.resolve(ProductRoute.REPAIR, HostCapabilities.full()));
        assertSame(ProductRoute.DESKTOP, StartupTarget.LAST_PAGE.resolve(ProductRoute.SERVICE_CONTROL, HostCapabilities.full()));
        assertSame(StartupTarget.DESKTOP, StartupTarget.fromPersistenceKey("repair"));
        assertSame(StartupTarget.DESKTOP, StartupTarget.fromPersistenceKey("service-control"));
    }

    @Test public void startupTargetAcceptsOnlyCurrentKeys() {
        assertSame(StartupTarget.LAST_PAGE, StartupTarget.fromPersistenceKey("last"));
        assertSame(StartupTarget.BASIC, StartupTarget.fromPersistenceKey("basic"));
        assertSame(StartupTarget.ADVANCED, StartupTarget.fromPersistenceKey("advanced"));
        assertSame(StartupTarget.DESKTOP, StartupTarget.fromPersistenceKey("operit"));
        assertSame(StartupTarget.DESKTOP, StartupTarget.fromPersistenceKey("pi-web"));
        HostCapabilities noAdvanced = new HostCapabilities(true, false, true, true,
            true, true, true, true);
        assertSame(ProductRoute.DESKTOP, StartupTarget.ADVANCED.resolve(ProductRoute.ADVANCED, noAdvanced));
    }

    @Test public void desktopReleasesAfterTenMinutesUnlessBestEffortKeepIsSelected() {
        DesktopResidencyPolicy normal = DesktopResidencyPolicy.defaultPolicy();
        assertEquals(600_000L, normal.releaseDelayMillis);
        assertFalse(normal.shouldRelease(599_999L));
        assertTrue(normal.shouldRelease(600_000L));
        DesktopResidencyPolicy keep = DesktopResidencyPolicy.keepBestEffort();
        assertFalse(keep.shouldRelease(Long.MAX_VALUE));
        assertFalse(keep.guaranteesProcessSurvival());
    }

    @Test public void runtimeConnectionRedactsTokenFromString() {
        RuntimeConnection connection = new RuntimeConnection("127.0.0.1:20087/", "very-secret", "http://127.0.0.1:8765/");
        assertEquals("http://127.0.0.1:20087", connection.serviceManagerBaseUrl);
        assertFalse(connection.toString().contains("very-secret"));
        assertTrue(connection.toString().contains("[REDACTED]"));
    }
}
