package com.termux.app.openhouse;

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class OpenHouseInstallControllerStageSequenceTest {

    @Test
    public void runtimeEnvironmentSequenceInstallsAndStartsRuntimeComponentsOnce() throws Exception {
        Assert.assertEquals(Arrays.asList(
            "PREPARE",
            "TERMUX_PACKAGES",
            "INSTALL_TERMUX_NODE",
            "RUNTIME_COMPONENTS",
            "START_SMALLPHONE",
            "INSTALL_UBUNTU",
            "UBUNTU_PACKAGES",
            "CONFIGURE_ENTRY_UBUNTU"
        ), stageNames("RUNTIME_ENVIRONMENT_STAGE_SEQUENCE"));
    }

    @Test
    public void aiFeaturesSequenceOnlyRestartsRegistrationBeforeAiStages() throws Exception {
        List<String> stages = stageNames("AI_FEATURES_STAGE_SEQUENCE");

        Assert.assertEquals(Arrays.asList(
            "START_SMALLPHONE",
            "INSTALL_NODE",
            "SYNC_OFFICIAL_DOCS",
            "INSTALL_AIONUI",
            "SYNC_OPENHOUSE_REGISTRY"
        ), stages);
        Assert.assertFalse(stages.contains("RUNTIME_COMPONENTS"));
    }

    @Test
    public void fullSequenceRemainsUnchanged() throws Exception {
        Assert.assertEquals(Arrays.asList(
            "PREPARE",
            "TERMUX_PACKAGES",
            "INSTALL_TERMUX_NODE",
            "RUNTIME_COMPONENTS",
            "START_SMALLPHONE",
            "INSTALL_UBUNTU",
            "UBUNTU_PACKAGES",
            "CONFIGURE_ENTRY_UBUNTU",
            "INSTALL_NODE",
            "SYNC_OFFICIAL_DOCS",
            "INSTALL_AIONUI",
            "SYNC_OPENHOUSE_REGISTRY"
        ), stageNames("FULL_STAGE_SEQUENCE"));
    }

    @Test
    public void standaloneAiFeaturesStillRequiresPreparedRuntime() throws Exception {
        Method method = OpenHouseInstallController.class.getDeclaredMethod(
            "requiresPreparedRuntime",
            OpenHouseInstallState.TaskScope.class
        );
        method.setAccessible(true);

        Assert.assertEquals(Boolean.TRUE, method.invoke(null, OpenHouseInstallState.TaskScope.AI_FEATURES));
        Assert.assertEquals(Boolean.FALSE, method.invoke(null, OpenHouseInstallState.TaskScope.RUNTIME_ENVIRONMENT));
        Assert.assertEquals(Boolean.FALSE, method.invoke(null, OpenHouseInstallState.TaskScope.FULL));
    }

    private static List<String> stageNames(String fieldName) throws Exception {
        Field field = OpenHouseInstallController.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        Object[] stages = (Object[]) field.get(null);
        List<String> names = new ArrayList<>(stages.length);
        for (Object stage : stages) {
            names.add(((Enum<?>) stage).name());
        }
        return names;
    }
}
