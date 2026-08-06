package com.wuxianpi.openhouse.feature.workspace

import android.content.Context
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import com.wuxianpi.openhouse.core.HostCapabilities
import com.wuxianpi.openhouse.core.ProductRoute
import com.wuxianpi.openhouse.core.rescue.RescueProcessState
import com.wuxianpi.openhouse.core.workspace.WorkspaceCatalog
import com.wuxianpi.openhouse.core.workspace.WorkspaceDestination
import com.wuxianpi.openhouse.feature.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkspaceSidebarTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun rescueCloseActionTracksProcessState() {
        val container = LinearLayout(context)
        var closeRequests = 0
        val sidebar = WorkspaceSidebar(
            context = context,
            container = container,
            onSelected = {},
            onCloseRescue = { closeRequests++ },
        )
        val entries = WorkspaceCatalog.applications(emptyList(), HostCapabilities.full())

        sidebar.bind(entries, RescueProcessState.BACKGROUND)

        val closeButton = rescueCloseButton(container, entries)
        assertEquals(View.VISIBLE, closeButton.visibility)
        assertTrue(closeButton.isEnabled)
        closeButton.performClick()
        assertEquals(1, closeRequests)

        sidebar.setRescueState(RescueProcessState.STOPPING)
        assertFalse(rescueCloseButton(container, entries).isEnabled)

        sidebar.setRescueState(RescueProcessState.NOT_RUNNING)
        assertEquals(View.GONE, rescueCloseButton(container, entries).visibility)
    }

    private fun rescueCloseButton(
        container: LinearLayout,
        entries: List<com.wuxianpi.openhouse.core.workspace.WorkspaceCatalogEntry>,
    ): ImageButton {
        val rescueIndex = entries.indexOfFirst { entry ->
            (entry.destination as? WorkspaceDestination.Route)?.route == ProductRoute.REPAIR
        }
        val row = container.getChildAt(rescueIndex) as LinearLayout
        return row.findViewById(R.id.workspace_item_close)
    }
}
