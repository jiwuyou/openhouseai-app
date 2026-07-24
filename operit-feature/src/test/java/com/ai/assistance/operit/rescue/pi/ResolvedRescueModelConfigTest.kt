package com.ai.assistance.operit.rescue.pi

import com.ai.assistance.operit.data.model.ModelConfigData
import org.junit.Assert.assertEquals
import org.junit.Test

class ResolvedRescueModelConfigTest {
    @Test
    fun `selected rescue model index becomes the engine model without mutating registry config`() {
        val config =
            ModelConfigData(
                id = "repair",
                name = "Repair",
                modelName = "model-a,model-b,model-c",
            )

        val resolved =
            ResolvedRescueModelConfig(
                selection = RescueModelSelection(configId = "repair", modelIndex = 1),
                config = config,
            )

        assertEquals("model-b", resolved.selectedModelName)
        assertEquals("model-b", resolved.selectedConfig.modelName)
        assertEquals("model-a,model-b,model-c", config.modelName)
    }

    @Test
    fun `editing selected model preserves the rest of the rescue registry`() {
        val resolved =
            ResolvedRescueModelConfig(
                selection = RescueModelSelection(configId = "repair", modelIndex = 1),
                config =
                    ModelConfigData(
                        id = "repair",
                        name = "Repair",
                        modelName = "model-a,model-b,model-c",
                    ),
            )

        assertEquals(
            "model-a,model-b-updated,model-c",
            resolved.configWithSelectedModelName("model-b-updated").modelName,
        )
    }
}
