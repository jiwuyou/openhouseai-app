package com.wuxianpi.openhouse.feature.workspace

import android.content.Context
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import com.wuxianpi.openhouse.core.workspace.WorkspaceCatalogEntry
import com.wuxianpi.openhouse.core.workspace.WorkspaceDestination
import com.wuxianpi.openhouse.feature.R

class WorkspaceSidebar(
    private val context: Context,
    private val container: LinearLayout,
    private val onSelected: (WorkspaceDestination) -> Unit,
) {
    fun bind(entries: List<WorkspaceCatalogEntry>) {
        container.removeAllViews()
        entries.forEach { entry ->
            val button = LayoutInflater.from(context)
                .inflate(R.layout.item_workspace_sidebar, container, false) as Button
            button.text = entry.title
            button.isEnabled = true
            button.contentDescription = entry.subtitle.ifBlank { entry.title }
            button.setOnClickListener { onSelected(entry.destination) }
            container.addView(button)
        }
    }
}
