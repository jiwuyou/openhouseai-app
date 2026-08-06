package com.wuxianpi.openhouse.feature.workspace

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.Button
import android.widget.LinearLayout
import com.wuxianpi.openhouse.core.ProductRoute
import com.wuxianpi.openhouse.core.rescue.RescueProcessState
import com.wuxianpi.openhouse.core.workspace.WorkspaceCatalogEntry
import com.wuxianpi.openhouse.core.workspace.WorkspaceDestination
import com.wuxianpi.openhouse.feature.R

class WorkspaceSidebar(
    private val context: Context,
    private val container: LinearLayout,
    private val onSelected: (WorkspaceDestination) -> Unit,
    private val onCloseRescue: () -> Unit = {},
) {
    private var entries: List<WorkspaceCatalogEntry> = emptyList()
    private var rescueState: RescueProcessState = RescueProcessState.NOT_RUNNING

    fun bind(
        entries: List<WorkspaceCatalogEntry>,
        rescueState: RescueProcessState = RescueProcessState.NOT_RUNNING,
    ) {
        this.entries = entries
        this.rescueState = rescueState
        render()
    }

    fun setRescueState(state: RescueProcessState) {
        if (rescueState == state) return
        rescueState = state
        render()
    }

    private fun render() {
        container.removeAllViews()
        entries.forEach { entry ->
            val row = LayoutInflater.from(context)
                .inflate(R.layout.item_workspace_sidebar, container, false) as LinearLayout
            val button = row.findViewById<Button>(R.id.workspace_item_button)
            val closeButton = row.findViewById<ImageButton>(R.id.workspace_item_close)
            button.text = entry.title
            button.isEnabled = true
            button.contentDescription = entry.subtitle.ifBlank { entry.title }
            button.setOnClickListener { onSelected(entry.destination) }

            val isRescue =
                (entry.destination as? WorkspaceDestination.Route)?.route == ProductRoute.REPAIR
            closeButton.visibility = if (isRescue && rescueState.isRunningLike()) {
                View.VISIBLE
            } else {
                View.GONE
            }
            closeButton.isEnabled = rescueState != RescueProcessState.STOPPING
            closeButton.contentDescription = "关闭维修模式"
            closeButton.setOnClickListener { onCloseRescue() }
            container.addView(row)
        }
    }
}
