package com.wuxianpi.openhouse.feature.workspace

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.wuxianpi.openhouse.core.ProductRoute
import com.wuxianpi.openhouse.core.rescue.RescueProcessState
import com.wuxianpi.openhouse.core.workspace.ComponentServiceState
import com.wuxianpi.openhouse.core.workspace.ComponentServiceSummary
import com.wuxianpi.openhouse.core.workspace.WorkspaceCatalogEntry
import com.wuxianpi.openhouse.core.workspace.WorkspaceDestination
import com.wuxianpi.openhouse.feature.R

class WorkspaceSidebar(
    private val context: Context,
    private val container: LinearLayout,
    private val onSelected: (WorkspaceDestination) -> Unit,
    private val onCloseRescue: () -> Unit = {},
    private val onPinnedChanged: (WorkspaceCatalogEntry, Boolean) -> Unit = { _, _ -> },
    private val onServiceRunningChanged: (WorkspaceCatalogEntry, Boolean) -> Unit = { _, _ -> },
) {
    private var entries: List<WorkspaceCatalogEntry> = emptyList()
    private var rescueState: RescueProcessState = RescueProcessState.NOT_RUNNING
    private var pinnedIds: Set<String> = emptySet()
    private var serviceStates: Map<String, ComponentServiceSummary> = emptyMap()
    private var pendingServiceActionIds: Set<String> = emptySet()

    fun bind(
        entries: List<WorkspaceCatalogEntry>,
        rescueState: RescueProcessState = RescueProcessState.NOT_RUNNING,
        pinnedIds: Set<String> = emptySet(),
        serviceStates: Map<String, ComponentServiceSummary> = emptyMap(),
        pendingServiceActionIds: Set<String> = emptySet(),
    ) {
        this.entries = entries
        this.rescueState = rescueState
        this.pinnedIds = pinnedIds
        this.serviceStates = serviceStates
        this.pendingServiceActionIds = pendingServiceActionIds
        render()
    }

    fun setRescueState(state: RescueProcessState) {
        if (rescueState == state) return
        rescueState = state
        render()
    }

    private fun render() {
        container.removeAllViews()
        val (pinned, regular) = entries.partition { normalizedId(it) in pinnedIds }
        if (pinned.isNotEmpty()) {
            addSection("置顶应用")
            pinned.forEach(::addEntry)
        }
        if (regular.isNotEmpty()) {
            addSection("全部应用")
            regular.forEach(::addEntry)
        }
    }

    private fun addSection(text: String) {
        container.addView(TextView(context).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(context, R.color.oh_text_secondary))
            textSize = 12f
            setPadding(0, dp(14), 0, dp(2))
        })
    }

    private fun addEntry(entry: WorkspaceCatalogEntry) {
            val row = LayoutInflater.from(context)
                .inflate(R.layout.item_workspace_sidebar, container, false) as LinearLayout
            val button = row.findViewById<Button>(R.id.workspace_item_button)
            val statusView = row.findViewById<View>(R.id.workspace_item_status)
            val pinButton = row.findViewById<ImageButton>(R.id.workspace_item_pin)
            val powerButton = row.findViewById<ImageButton>(R.id.workspace_item_power)
            val id = normalizedId(entry)
            val pinned = id in pinnedIds
            val summary = serviceStates[id]
            button.text = entry.title
            button.isEnabled = true
            button.contentDescription = buildString {
                append(entry.subtitle.ifBlank { entry.title })
                serviceStateLabel(summary?.state)?.let { append("，状态：").append(it) }
            }
            button.setOnClickListener { onSelected(entry.destination) }

            pinButton.contentDescription = if (pinned) "取消置顶 ${entry.title}" else "置顶 ${entry.title}"
            pinButton.setColorFilter(
                ContextCompat.getColor(context, if (pinned) R.color.oh_accent else R.color.oh_text_secondary),
            )
            pinButton.setOnClickListener { onPinnedChanged(entry, !pinned) }

            val isRescue =
                (entry.destination as? WorkspaceDestination.Route)?.route == ProductRoute.REPAIR
            if (isRescue) {
                bindRescueState(statusView, powerButton)
            } else {
                bindServiceState(entry, summary, statusView, powerButton)
            }
            container.addView(row)
    }

    private fun bindRescueState(statusView: View, powerButton: ImageButton) {
        val state = when (rescueState) {
            RescueProcessState.FOREGROUND, RescueProcessState.BACKGROUND -> ComponentServiceState.RUNNING
            RescueProcessState.STOPPING -> ComponentServiceState.STOPPING
            RescueProcessState.NOT_RUNNING -> ComponentServiceState.NONE
        }
        renderStatus(statusView, state)
        powerButton.visibility = if (rescueState.isRunningLike()) View.VISIBLE else View.GONE
        powerButton.isEnabled = rescueState != RescueProcessState.STOPPING
        powerButton.alpha = if (powerButton.isEnabled) 1f else 0.45f
        powerButton.contentDescription = "关闭维修模式"
        powerButton.setOnClickListener { onCloseRescue() }
    }

    private fun bindServiceState(
        entry: WorkspaceCatalogEntry,
        summary: ComponentServiceSummary?,
        statusView: View,
        powerButton: ImageButton,
    ) {
        val state = summary?.state ?: if (entry.component.isServiceBacked) {
            ComponentServiceState.UNKNOWN
        } else {
            ComponentServiceState.NONE
        }
        renderStatus(statusView, state)
        powerButton.visibility = if (summary?.hasServices == true || entry.component.isServiceBacked) {
            View.VISIBLE
        } else {
            View.GONE
        }
        val desiredRunning = when (state) {
            ComponentServiceState.RUNNING, ComponentServiceState.MIXED -> false
            ComponentServiceState.STOPPED, ComponentServiceState.FAILED -> true
            else -> null
        }
        val pending = normalizedId(entry) in pendingServiceActionIds
        powerButton.isEnabled = desiredRunning != null && !pending
        powerButton.alpha = if (powerButton.isEnabled) 1f else 0.45f
        powerButton.contentDescription = when {
            pending -> "正在操作 ${entry.title} 服务"
            desiredRunning == true -> "启动 ${entry.title} 服务"
            desiredRunning == false -> "关闭 ${entry.title} 服务"
            else -> "${entry.title} 服务${serviceStateLabel(state) ?: "状态未知"}"
        }
        powerButton.setOnClickListener {
            desiredRunning?.let { onServiceRunningChanged(entry, it) }
        }
    }

    private fun renderStatus(view: View, state: ComponentServiceState) {
        if (state == ComponentServiceState.NONE) {
            view.visibility = View.INVISIBLE
            return
        }
        view.visibility = View.VISIBLE
        val color = when (state) {
            ComponentServiceState.RUNNING -> ContextCompat.getColor(context, R.color.oh_accent)
            ComponentServiceState.STARTING, ComponentServiceState.STOPPING, ComponentServiceState.MIXED ->
                Color.rgb(191, 124, 0)
            ComponentServiceState.FAILED -> ContextCompat.getColor(context, R.color.oh_danger)
            ComponentServiceState.STOPPED, ComponentServiceState.UNKNOWN ->
                ContextCompat.getColor(context, R.color.oh_text_secondary)
            ComponentServiceState.NONE -> Color.TRANSPARENT
        }
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (state == ComponentServiceState.UNKNOWN) {
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), color)
            } else {
                setColor(color)
            }
        }
    }

    private fun serviceStateLabel(state: ComponentServiceState?): String? = when (state) {
        ComponentServiceState.RUNNING -> "运行中"
        ComponentServiceState.STARTING -> "启动中"
        ComponentServiceState.STOPPING -> "停止中"
        ComponentServiceState.STOPPED -> "已停止"
        ComponentServiceState.FAILED -> "失败"
        ComponentServiceState.MIXED -> "部分运行"
        ComponentServiceState.UNKNOWN -> "未知"
        ComponentServiceState.NONE, null -> null
    }

    private fun normalizedId(entry: WorkspaceCatalogEntry): String =
        WorkspaceDestination.normalizeId(entry.component.id)

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
