package com.termux.app.openhouse.servicecontrol;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

public class ServiceManagerServiceResolverTest {

    @Test
    public void resolvesPiAgentComponentToAvailablePiWebService() throws Exception {
        ServiceManagerServiceResolver.Resolution resolution = ServiceManagerServiceResolver.resolve(
            "pi-agent",
            Arrays.asList("yuanshengwuxianpi", "pi-agent"),
            Arrays.asList(service("yuanshengwuxianpi", "WuxianPi", "openhouse-component:pi-agent")));

        Assert.assertEquals(Arrays.asList("yuanshengwuxianpi"), resolution.serviceIds);
        Assert.assertTrue(resolution.missingServiceIds.isEmpty());
    }

    @Test
    public void resolvesCanonicalAionUiWebService() throws Exception {
        ServiceManagerServiceResolver.Resolution resolution = ServiceManagerServiceResolver.resolve(
            "aionui-web",
            Arrays.asList("aionui-web"),
            Arrays.asList(service("aionui-web", "aionui-web", "openhouse-component:aionui-web")));

        Assert.assertEquals(Arrays.asList("aionui-web"), resolution.serviceIds);
        Assert.assertTrue(resolution.missingServiceIds.isEmpty());
    }

    @Test
    public void doesNotRedirectCanonicalAionUiWebIdToLegacyAionUiService() throws Exception {
        ServiceManagerServiceResolver.Resolution resolution = ServiceManagerServiceResolver.resolve(
            "aionui-web",
            Arrays.asList("aionui-web"),
            Arrays.asList(service("aionui", "aionui", "openhouse-component:aionui")));

        Assert.assertTrue(resolution.serviceIds.isEmpty());
        Assert.assertEquals(Arrays.asList("aionui-web"), resolution.missingServiceIds);
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
