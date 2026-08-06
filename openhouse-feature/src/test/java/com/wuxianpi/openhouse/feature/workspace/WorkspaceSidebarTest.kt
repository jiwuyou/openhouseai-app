package com.wuxianpi.openhouse.feature.workspace

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import com.wuxianpi.openhouse.core.HostCapabilities
import com.wuxianpi.openhouse.core.ProductRoute
import com.wuxianpi.openhouse.core.registry.OpenHouseComponentParser
import com.wuxianpi.openhouse.core.registry.RegistryManifest
import com.wuxianpi.openhouse.core.rescue.RescueProcessState
import com.wuxianpi.openhouse.core.workspace.ComponentServiceState
import com.wuxianpi.openhouse.core.workspace.ComponentServiceSummary
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

        val closeButton = actionButton(container, "维修模式")
        assertEquals(View.VISIBLE, closeButton.visibility)
        assertTrue(closeButton.isEnabled)
        closeButton.performClick()
        assertEquals(1, closeRequests)

        sidebar.setRescueState(RescueProcessState.STOPPING)
        assertFalse(actionButton(container, "维修模式").isEnabled)

        sidebar.setRescueState(RescueProcessState.NOT_RUNNING)
        assertEquals(View.GONE, actionButton(container, "维修模式").visibility)
    }

    @Test
    fun pinnedAndRunningComponentRendersBeforeRegularAppsWithStopAction() {
        val component = OpenHouseComponentParser().parse(
            RegistryManifest.fromManifestJson(
                """{
                    "id":"notes",
                    "title":"Notes",
                    "entry":{"type":"webview","url":"http://127.0.0.1:9000/"},
                    "controlEntry":{"type":"service-control","serviceNames":["notes"]}
                }""".trimIndent(),
            ),
            "test",
        )
        val entries = WorkspaceCatalog.applications(listOf(component), HostCapabilities.full())
        val container = LinearLayout(context)
        var pinnedValue: Boolean? = null
        var runningValue: Boolean? = null
        val sidebar = WorkspaceSidebar(
            context = context,
            container = container,
            onSelected = {},
            onPinnedChanged = { _, pinned -> pinnedValue = pinned },
            onServiceRunningChanged = { _, running -> runningValue = running },
        )

        sidebar.bind(
            entries = entries,
            pinnedIds = setOf("notes"),
            serviceStates = mapOf(
                "notes" to ComponentServiceSummary(
                    componentId = "notes",
                    serviceIds = listOf("notes"),
                    state = ComponentServiceState.RUNNING,
                ),
            ),
        )

        val row = row(container, "Notes")
        assertEquals(View.VISIBLE, row.findViewById<View>(R.id.workspace_item_status).visibility)
        row.findViewById<ImageButton>(R.id.workspace_item_pin).performClick()
        row.findViewById<ImageButton>(R.id.workspace_item_power).performClick()
        assertEquals(false, pinnedValue)
        assertEquals(false, runningValue)
    }

    @Test
    fun pendingServiceActionDisablesPowerWithoutChangingDisplayedState() {
        val component = OpenHouseComponentParser().parse(
            RegistryManifest.fromManifestJson(
                """{
                    "id":"notes",
                    "title":"Notes",
                    "entry":{"type":"webview","url":"http://127.0.0.1:9000/"},
                    "controlEntry":{"type":"service-control","serviceNames":["notes"]}
                }""".trimIndent(),
            ),
            "test",
        )
        val container = LinearLayout(context)
        val sidebar = WorkspaceSidebar(context, container, onSelected = {})

        sidebar.bind(
            entries = WorkspaceCatalog.applications(listOf(component), HostCapabilities.full()),
            serviceStates = mapOf(
                "notes" to ComponentServiceSummary(
                    componentId = "notes",
                    serviceIds = listOf("notes"),
                    state = ComponentServiceState.RUNNING,
                ),
            ),
            pendingServiceActionIds = setOf("notes"),
        )

        val row = row(container, "Notes")
        assertFalse(row.findViewById<ImageButton>(R.id.workspace_item_power).isEnabled)
        assertEquals(
            "正在操作 Notes 服务",
            row.findViewById<ImageButton>(R.id.workspace_item_power).contentDescription,
        )
    }

    private fun actionButton(container: LinearLayout, title: String): ImageButton =
        row(container, title).findViewById(R.id.workspace_item_power)

    private fun row(container: LinearLayout, title: String): LinearLayout =
        (0 until container.childCount)
            .map(container::getChildAt)
            .filterIsInstance<LinearLayout>()
            .first { it.findViewById<Button>(R.id.workspace_item_button)?.text?.toString() == title }
}
