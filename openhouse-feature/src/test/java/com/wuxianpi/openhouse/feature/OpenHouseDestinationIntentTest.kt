package com.wuxianpi.openhouse.feature

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.wuxianpi.openhouse.core.ProductRoute
import com.wuxianpi.openhouse.core.workspace.WorkspaceDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpenHouseDestinationIntentTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun routeDestinationUsesStartupRouteExtra() {
        val intent = OpenHouseFeature.createDestinationIntent(
            context,
            WorkspaceDestination.Route(ProductRoute.SETTINGS),
        )

        assertEquals(
            ProductRoute.SETTINGS.name,
            intent.getStringExtra(OpenHouseFeature.EXTRA_STARTUP_ROUTE),
        )
        assertNull(intent.getStringExtra(OpenHouseFeature.EXTRA_STARTUP_COMPONENT_ID))
    }

    @Test
    fun componentDestinationUsesNormalizedComponentId() {
        val intent = OpenHouseFeature.createDestinationIntent(
            context,
            WorkspaceDestination.Component(" WuxianPi Agent "),
        )

        assertEquals(
            "wuxianpi-agent",
            intent.getStringExtra(OpenHouseFeature.EXTRA_STARTUP_COMPONENT_ID),
        )
        assertNull(intent.getStringExtra(OpenHouseFeature.EXTRA_STARTUP_ROUTE))
    }
}
