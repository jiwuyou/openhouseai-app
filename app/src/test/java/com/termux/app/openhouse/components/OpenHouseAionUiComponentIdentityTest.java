package com.termux.app.openhouse.components;

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class OpenHouseAionUiComponentIdentityTest {

    @Test
    public void builtinUsesCanonicalAionUiIdentityExactlyOnce() throws Exception {
        Method method = OpenHouseComponentRegistry.class.getDeclaredMethod("createBuiltinComponents");
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<OpenHouseComponent> components = (List<OpenHouseComponent>) method.invoke(null);

        int canonicalCount = 0;
        int legacyCount = 0;
        for (OpenHouseComponent component : components) {
            if ("aionui-web".equals(component.id)) {
                canonicalCount++;
                Assert.assertEquals(Collections.singletonList("aionui-web"), component.serviceNames);
                Assert.assertEquals(
                    Collections.singletonList("service-manager://services/aionui-web"),
                    component.serviceRefs);
            } else if ("aionui".equals(component.id)) {
                legacyCount++;
            }
        }

        Assert.assertEquals(1, canonicalCount);
        Assert.assertEquals(0, legacyCount);
    }

    @Test
    public void legacyExtensionIsCanonicalizedBeforeBuiltinMerge() {
        OpenHouseComponent legacy = new OpenHouseComponent(
            "aionui",
            "AionUi",
            "legacy extension",
            "ai",
            30,
            "app",
            "A",
            30,
            true,
            false,
            true,
            OpenHouseComponent.EntryType.WEBVIEW,
            "http://127.0.0.1:25808/",
            null,
            null,
            "控制",
            true,
            true,
            false,
            false,
            "extension",
            Arrays.asList("aionui"),
            Arrays.asList("service-manager://services/aionui"));

        OpenHouseComponent canonical = OpenHouseComponentRegistry.canonicalizeAionUiComponent(legacy);

        Assert.assertEquals("aionui-web", canonical.id);
        Assert.assertEquals(Collections.singletonList("aionui-web"), canonical.serviceNames);
        Assert.assertEquals(
            Collections.singletonList("service-manager://services/aionui-web"),
            canonical.serviceRefs);
    }
}
