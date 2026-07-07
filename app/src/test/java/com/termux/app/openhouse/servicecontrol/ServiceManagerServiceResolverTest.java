package com.termux.app.openhouse.servicecontrol;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

public class ServiceManagerServiceResolverTest {

    @Test
    public void resolvesPiAgentComponentToAvailablePiWebService() throws Exception {
        ServiceManagerServiceResolver.Resolution resolution = ServiceManagerServiceResolver.resolve(
            "pi-agent",
            Arrays.asList("pi-web", "pi-agent"),
            Arrays.asList(service("pi-web", "pi-web", "openhouse-component:pi-web")));

        Assert.assertEquals(Arrays.asList("pi-web"), resolution.serviceIds);
        Assert.assertTrue(resolution.missingServiceIds.isEmpty());
    }

    @Test
    public void resolvesLegacyAionUiWebIdToAionUiService() throws Exception {
        ServiceManagerServiceResolver.Resolution resolution = ServiceManagerServiceResolver.resolve(
            "aionui-web",
            Arrays.asList("aionui-web"),
            Arrays.asList(service("aionui", "aionui", "openhouse-component:aionui")));

        Assert.assertEquals(Arrays.asList("aionui"), resolution.serviceIds);
        Assert.assertTrue(resolution.missingServiceIds.isEmpty());
    }

    @Test
    public void deduplicatesServiceNameAndCanonicalServiceRef() throws Exception {
        String serviceId = "805035faf4666b250bbb228eb2567726";
        ServiceManagerServiceResolver.Resolution resolution = ServiceManagerServiceResolver.resolve(
            "cliproxyapi",
            Arrays.asList("cliproxyapi", serviceId),
            Arrays.asList(service(serviceId, "cliproxyapi", "openhouse-component:cliproxyapi")));

        Assert.assertEquals(Arrays.asList(serviceId), resolution.serviceIds);
        Assert.assertTrue(resolution.missingServiceIds.isEmpty());
    }

    private static ServiceManagerService service(String id, String name, String tag) {
        return new ServiceManagerService(
            id,
            name,
            name,
            "",
            "process",
            "",
            null,
            "",
            "",
            Arrays.asList(tag),
            "{}");
    }
}
