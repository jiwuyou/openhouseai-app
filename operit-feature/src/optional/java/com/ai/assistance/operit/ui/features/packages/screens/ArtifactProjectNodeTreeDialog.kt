package com.ai.assistance.operit.ui.features.packages.screens

import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.stringResource
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.api.ArtifactProjectDetailResponse
import com.ai.assistance.operit.data.api.ArtifactProjectNodeResponse
import com.ai.assistance.operit.data.api.GitHubIssue

@Composable
fun ArtifactProjectNodeTreeLoadingDialog(
    projectId: String,
    onDismissRequest: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties =
            DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                usePlatformDefaultWidth = false
            )
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 10.dp
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                        text = stringResource(R.string.artifact_project_node_view_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = projectId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(R.string.artifact_project_nodes_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun ArtifactProjectNodeTreeDialog(
    project: ArtifactProjectDetailResponse,
    onDismissRequest: () -> Unit,
    onSelectNode: (GitHubIssue) -> Unit = {},
    selectedNodeIds: Set<String> = emptySet(),
    onToggleNodeSelection: ((ArtifactProjectNodeResponse) -> Unit)? = null,
    onConfirmSelection: (() -> Unit)? = null,
    confirmSelectionEnabled: Boolean = true
) {
    val isSelectionMode = onToggleNodeSelection != null || onConfirmSelection != null
    Dialog(
        onDismissRequest = onDismissRequest,
        properties =
            DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                usePlatformDefaultWidth = false
            )
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 10.dp
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.align(Alignment.CenterStart),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = project.projectDisplayName.ifBlank { project.projectId },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            if (isSelectionMode) {
                                Text(
                                    text = stringResource(R.string.artifact_project_node_selection_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        TextButton(
                            onClick = {
                                if (isSelectionMode) {
                                    onConfirmSelection?.invoke()
                                } else {
                                    onDismissRequest()
                                }
                            },
                            enabled = !isSelectionMode || confirmSelectionEnabled,
                            modifier = Modifier.align(Alignment.CenterEnd)
                        ) {
                            Text(
                                if (isSelectionMode) {
                                    stringResource(R.string.confirm)
                                } else {
                                    stringResource(R.string.close)
                                }
                            )
                        }
                    }

                    project.projectDescription
                        .takeIf { it.isNotBlank() }
                        ?.let { description ->
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 240.dp)
                                        .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                    ArtifactProjectTreeCanvas(
                        project = project,
                        onSelectNode =
                            if (isSelectionMode) {
                                null
                            } else {
                                { issue ->
                                    onDismissRequest()
                                    onSelectNode(issue)
                                }
                            },
                        selectedNodeIds = selectedNodeIds,
                        onToggleNodeSelection = onToggleNodeSelection
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtifactProjectTreeCanvas(
    project: ArtifactProjectDetailResponse,
    onSelectNode: ((GitHubIssue) -> Unit)?,
    selectedNodeIds: Set<String>,
    onToggleNodeSelection: ((ArtifactProjectNodeResponse) -> Unit)?
) {
    val density = LocalDensity.current
    val metrics = remember(density) { buildArtifactTreeMetrics(density) }
    val layout = remember(project, metrics) { buildArtifactProjectTreeLayout(project, metrics) }
    var viewportSize by remember(project.projectId) { mutableStateOf(IntSize.Zero) }
    var scale by remember(project.projectId) { mutableStateOf(1f) }
    var offset by remember(project.projectId) { mutableStateOf(Offset.Zero) }
    var lastTransformGestureAt by remember(project.projectId) { mutableStateOf(0L) }
    val viewPaddingPx = with(density) { VIEWPORT_FIT_PADDING.toPx() }

    LaunchedEffect(project.projectId, viewportSize, layout.totalWidth, layout.totalHeight) {
        if (viewportSize == IntSize.Zero) {
            return@LaunchedEffect
        }
        val availableWidth = (viewportSize.width.toFloat() - viewPaddingPx * 2f).coerceAtLeast(1f)
        val availableHeight = (viewportSize.height.toFloat() - viewPaddingPx * 2f).coerceAtLeast(1f)
        val fitScale =
            minOf(
                availableWidth / layout.totalWidth,
                availableHeight / layout.totalHeight,
                1f
            ).coerceIn(MIN_CANVAS_SCALE, MAX_CANVAS_SCALE)
        scale = fitScale
        offset =
            Offset(
                x = (viewportSize.width.toFloat() - layout.totalWidth * fitScale) / 2f,
                y = (viewportSize.height.toFloat() - layout.totalHeight * fitScale) / 2f
            )
    }

    val datePaint =
        remember(density) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = with(density) { 10.sp.toPx() }
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }
        }
    val authorPaint =
        remember(density) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = with(density) { 11.sp.toPx() }
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
        }
    val versionPaint =
        remember(density) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = with(density) { 11.sp.toPx() }
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
        }

    val viewportBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
    val edgeColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)
    val closedEdgeColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    val defaultFill = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.20f)
    val defaultBorder = MaterialTheme.colorScheme.primary
    val openFill = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.16f)
    val openBorder = MaterialTheme.colorScheme.primary.copy(alpha = 0.54f)
    val closedFill = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)
    val closedBorder = MaterialTheme.colorScheme.outline.copy(alpha = 0.54f)
    val selectedFill = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.34f)
    val selectedBorder = MaterialTheme.colorScheme.tertiary
    val primaryText = MaterialTheme.colorScheme.onSurface
    val secondaryText = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 280.dp, max = 560.dp)
                .background(
                    color = viewportBackground,
                    shape = RoundedCornerShape(20.dp)
                )
                .clip(RoundedCornerShape(20.dp))
                .onSizeChanged { viewportSize = it }
                .pointerInput(project.projectId) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        if (zoom != 1f || pan.getDistance() > 0.5f) {
                            lastTransformGestureAt = SystemClock.uptimeMillis()
                        }
                        val newScale = (scale * zoom).coerceIn(MIN_CANVAS_SCALE, MAX_CANVAS_SCALE)
                        val scaleFactor = newScale / scale
                        offset = centroid - (centroid - offset) * scaleFactor + pan
                        scale = newScale
                    }
                }
                .pointerInput(project.projectId) {
                    detectTapGestures(
                        onTap = { tapOffset ->
                            if (SystemClock.uptimeMillis() - lastTransformGestureAt < TRANSFORM_TAP_SUPPRESSION_MS) {
                                return@detectTapGestures
                            }
                            val worldPoint = canvasToWorld(tapOffset, offset, scale)
                            layout.nodes
                                .lastOrNull { it.rect.contains(worldPoint) }
                                ?.node
                                ?.let { tappedNode ->
                                    if (onToggleNodeSelection != null) {
                                        onToggleNodeSelection(tappedNode)
                                    } else {
                                        onSelectNode?.invoke(tappedNode.issue)
                                    }
                                }
                        },
                        onDoubleTap = {
                            val availableWidth = (viewportSize.width.toFloat() - viewPaddingPx * 2f).coerceAtLeast(1f)
                            val availableHeight = (viewportSize.height.toFloat() - viewPaddingPx * 2f).coerceAtLeast(1f)
                            val fitScale =
                                minOf(
                                    availableWidth / layout.totalWidth,
                                    availableHeight / layout.totalHeight,
                                    1f
                                ).coerceIn(MIN_CANVAS_SCALE, MAX_CANVAS_SCALE)
                            scale = fitScale
                            offset =
                                Offset(
                                    x = (viewportSize.width.toFloat() - layout.totalWidth * fitScale) / 2f,
                                    y = (viewportSize.height.toFloat() - layout.totalHeight * fitScale) / 2f
                                )
                        }
                    )
                }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            withTransform({
                translate(left = offset.x, top = offset.y)
                scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero)
            }) {
                val nodeById = layout.nodes.associateBy { it.node.nodeId }
                layout.nodes.forEach { child ->
                    child.node.parentNodeIds.forEach { parentNodeId ->
                        val parent = nodeById[parentNodeId] ?: return@forEach
                        drawArtifactEdge(
                            start = Offset(parent.rect.center.x, parent.rect.bottom),
                            end = Offset(child.rect.center.x, child.rect.top),
                            color =
                                if (parent.node.issue.state == "open" && child.node.issue.state == "open") {
                                    edgeColor
                                } else {
                                    closedEdgeColor
                                },
                            metrics = metrics
                        )
                    }
                }

                layout.nodes.forEach { layoutNode ->
                    val isSelected = layoutNode.node.nodeId in selectedNodeIds
                    val palette =
                        when {
                            isSelected ->
                                ArtifactTreeNodePalette(
                                    fillColor = selectedFill,
                                    borderColor = selectedBorder,
                                    primaryTextColor = primaryText,
                                    secondaryTextColor = secondaryText
                                )

                            layoutNode.node.nodeId == project.defaultNodeId && layoutNode.node.issue.state == "open" ->
                                ArtifactTreeNodePalette(
                                    fillColor = defaultFill,
                                    borderColor = defaultBorder,
                                    primaryTextColor = primaryText,
                                    secondaryTextColor = secondaryText
                                )

                            layoutNode.node.issue.state == "open" ->
                                ArtifactTreeNodePalette(
                                    fillColor = openFill,
                                    borderColor = openBorder,
                                    primaryTextColor = primaryText,
                                    secondaryTextColor = secondaryText
                                )

                            else ->
                                ArtifactTreeNodePalette(
                                    fillColor = closedFill,
                                    borderColor = closedBorder,
                                    primaryTextColor = secondaryText,
                                    secondaryTextColor = secondaryText.copy(alpha = 0.92f)
                                )
                        }
                    drawArtifactNode(
                        rect = layoutNode.rect,
                        node = layoutNode.node,
                        isDefault = layoutNode.node.nodeId == project.defaultNodeId,
                        isSelected = isSelected,
                        palette = palette,
                        metrics = metrics,
                        datePaint = datePaint,
                        authorPaint = authorPaint,
                        versionPaint = versionPaint
                    )
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArtifactNode(
    rect: Rect,
    node: ArtifactProjectNodeResponse,
    isDefault: Boolean,
    isSelected: Boolean,
    palette: ArtifactTreeNodePalette,
    metrics: ArtifactTreeMetrics,
    datePaint: Paint,
    authorPaint: Paint,
    versionPaint: Paint
) {
    val cornerRadius = CornerRadius(metrics.cornerRadius, metrics.cornerRadius)
    val borderWidth =
        when {
            isSelected -> metrics.borderWidth * 1.55f
            isDefault -> metrics.borderWidth * 1.18f
            else -> metrics.borderWidth
        }
    drawRoundRect(
        color = palette.fillColor,
        topLeft = rect.topLeft,
        size = rect.size,
        cornerRadius = cornerRadius
    )
    drawRoundRect(
        color = palette.borderColor,
        topLeft = rect.topLeft,
        size = rect.size,
        cornerRadius = cornerRadius,
        style = Stroke(width = borderWidth)
    )

    val dateText = compactArtifactDate(node.publishedAt ?: node.issue.created_at)
    val authorText =
        fitTextToWidth(
            text = node.publisherLogin.ifBlank { node.issue.user.login }.ifBlank { "unknown" },
            paint = authorPaint,
            maxWidth = rect.width - metrics.contentPadding * 2f
        )
    val versionText =
        fitTextToWidth(
            text = "v${node.version.ifBlank { "-" }}",
            paint = versionPaint,
            maxWidth = rect.width - metrics.contentPadding * 2f
        )

    datePaint.color = palette.secondaryTextColor.toArgb()
    authorPaint.color = palette.primaryTextColor.toArgb()
    versionPaint.color = palette.primaryTextColor.toArgb()

    val dateHeight = datePaint.lineHeightPx()
    val authorHeight = authorPaint.lineHeightPx()
    val versionHeight = versionPaint.lineHeightPx()
    val totalTextHeight = dateHeight + authorHeight + versionHeight + metrics.lineGap * 2f
    var top = rect.top + (rect.height - totalTextHeight) / 2f

    val dateBaseline = top - datePaint.fontMetrics.ascent
    top += dateHeight + metrics.lineGap
    val authorBaseline = top - authorPaint.fontMetrics.ascent
    top += authorHeight + metrics.lineGap
    val versionBaseline = top - versionPaint.fontMetrics.ascent

    val canvas = drawContext.canvas.nativeCanvas
    canvas.drawText(
        dateText,
        rect.center.x - datePaint.measureText(dateText) / 2f,
        dateBaseline,
        datePaint
    )
    canvas.drawText(
        authorText,
        rect.center.x - authorPaint.measureText(authorText) / 2f,
        authorBaseline,
        authorPaint
    )
    canvas.drawText(
        versionText,
        rect.center.x - versionPaint.measureText(versionText) / 2f,
        versionBaseline,
        versionPaint
    )
}

private data class ArtifactProjectTreeLayoutNode(
    val node: ArtifactProjectNodeResponse,
    val depth: Int,
    val slot: Float,
    val rect: Rect
)

private data class ArtifactProjectTreeLayout(
    val nodes: List<ArtifactProjectTreeLayoutNode>,
    val totalWidth: Float,
    val totalHeight: Float
)

private data class ArtifactTreeMetrics(
    val nodeWidth: Float,
    val nodeHeight: Float,
    val columnGap: Float,
    val rowGap: Float,
    val padding: Float,
    val cornerRadius: Float,
    val borderWidth: Float,
    val edgeWidth: Float,
    val arrowSize: Float,
    val contentPadding: Float,
    val lineGap: Float
)

private data class ArtifactTreeNodePalette(
    val fillColor: Color,
    val borderColor: Color,
    val primaryTextColor: Color,
    val secondaryTextColor: Color
)

private fun buildArtifactProjectTreeLayout(
    project: ArtifactProjectDetailResponse,
    metrics: ArtifactTreeMetrics
): ArtifactProjectTreeLayout {
    if (project.nodes.isEmpty()) {
        return ArtifactProjectTreeLayout(
            nodes = emptyList(),
            totalWidth = metrics.nodeWidth + metrics.padding * 2f,
            totalHeight = metrics.nodeHeight + metrics.padding * 2f
        )
    }

    val nodeById = project.nodes.associateBy { it.nodeId }
    val depthCache = mutableMapOf<String, Int>()

    fun nodeDepth(node: ArtifactProjectNodeResponse): Int {
        return depthCache.getOrPut(node.nodeId) {
            val parents = node.parentNodeIds.mapNotNull(nodeById::get)
            if (parents.isEmpty()) {
                0
            } else {
                parents.maxOf(::nodeDepth) + 1
            }
        }
    }

    val nodesByDepth = project.nodes.groupBy(::nodeDepth)
    val maxDepth = nodesByDepth.keys.maxOrNull() ?: 0
    val slotByNodeId = mutableMapOf<String, Float>()

    for (depth in 0..maxDepth) {
        val nodesAtDepth = nodesByDepth[depth].orEmpty()
        if (nodesAtDepth.isEmpty()) {
            continue
        }

        val orderedNodes =
            nodesAtDepth
                .mapIndexed { index, node ->
                    val preferredSlot =
                        node.parentNodeIds
                            .mapNotNull { slotByNodeId[it] }
                            .takeIf { it.isNotEmpty() }
                            ?.average()
                            ?.toFloat()
                    ArtifactProjectNodePlacement(
                        node = node,
                        preferredSlot = preferredSlot,
                        fallbackOrder = index.toFloat()
                    )
                }.sortedWith(
                    compareBy<ArtifactProjectNodePlacement>(
                        { it.preferredSlot ?: it.fallbackOrder },
                        { it.node.publishedAt ?: it.node.issue.created_at },
                        { it.node.nodeId }
                    )
                )

        val resolvedSlots =
            orderedNodes.map { it.preferredSlot ?: it.fallbackOrder }.toMutableList()
        for (index in 1 until resolvedSlots.size) {
            resolvedSlots[index] = maxOf(resolvedSlots[index], resolvedSlots[index - 1] + 1f)
        }
        val preferredMean =
            orderedNodes
                .map { it.preferredSlot ?: it.fallbackOrder }
                .average()
                .toFloat()
        val resolvedMean = resolvedSlots.average().toFloat()
        val shift = preferredMean - resolvedMean
        for (index in resolvedSlots.indices) {
            resolvedSlots[index] += shift
        }

        orderedNodes.forEachIndexed { index, placement ->
            slotByNodeId[placement.node.nodeId] = resolvedSlots[index]
        }
    }

    val minSlot = slotByNodeId.values.minOrNull() ?: 0f
    val maxSlot = slotByNodeId.values.maxOrNull() ?: 0f
    val orderedLayoutNodes =
        project.nodes
            .map { node ->
                val depth = nodeDepth(node)
                val slot = slotByNodeId[node.nodeId] ?: 0f
                val left = metrics.padding + (slot - minSlot) * (metrics.nodeWidth + metrics.columnGap)
                val top = metrics.padding + depth * (metrics.nodeHeight + metrics.rowGap)
                ArtifactProjectTreeLayoutNode(
                    node = node,
                    depth = depth,
                    slot = slot,
                    rect = Rect(left, top, left + metrics.nodeWidth, top + metrics.nodeHeight)
                )
            }.sortedWith(compareBy<ArtifactProjectTreeLayoutNode>({ it.depth }, { it.slot }))

    val totalWidth =
        metrics.padding * 2f +
            metrics.nodeWidth +
            (maxSlot - minSlot) * (metrics.nodeWidth + metrics.columnGap)
    val totalHeight =
        metrics.padding * 2f +
            metrics.nodeHeight +
            maxDepth * (metrics.nodeHeight + metrics.rowGap)

    return ArtifactProjectTreeLayout(
        nodes = orderedLayoutNodes,
        totalWidth = totalWidth,
        totalHeight = totalHeight
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArtifactEdge(
    start: Offset,
    end: Offset,
    color: Color,
    metrics: ArtifactTreeMetrics
) {
    val midY = (start.y + end.y) / 2f
    val path =
        Path().apply {
            moveTo(start.x, start.y)
            cubicTo(
                start.x,
                midY,
                end.x,
                midY,
                end.x,
                end.y
            )
        }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = metrics.edgeWidth, cap = StrokeCap.Round)
    )

    val leftWing = Offset(end.x - metrics.arrowSize * 0.55f, end.y - metrics.arrowSize)
    val rightWing = Offset(end.x + metrics.arrowSize * 0.55f, end.y - metrics.arrowSize)
    drawLine(
        color = color,
        start = leftWing,
        end = end,
        strokeWidth = metrics.edgeWidth,
        cap = StrokeCap.Round
    )
    drawLine(
        color = color,
        start = rightWing,
        end = end,
        strokeWidth = metrics.edgeWidth,
        cap = StrokeCap.Round
    )
}

private fun compactArtifactDate(value: String): String {
    return value.substringBefore('T').ifBlank { value }
}

private fun canvasToWorld(point: Offset, offset: Offset, scale: Float): Offset {
    return Offset(
        x = (point.x - offset.x) / scale,
        y = (point.y - offset.y) / scale
    )
}

private fun Paint.lineHeightPx(): Float {
    val metrics = fontMetrics
    return metrics.descent - metrics.ascent
}

private fun fitTextToWidth(
    text: String,
    paint: Paint,
    maxWidth: Float
): String {
    if (paint.measureText(text) <= maxWidth) {
        return text
    }
    val ellipsis = "…"
    var current = text
    while (current.isNotEmpty() && paint.measureText(current + ellipsis) > maxWidth) {
        current = current.dropLast(1)
    }
    return if (current.isEmpty()) ellipsis else current + ellipsis
}

private fun buildArtifactTreeMetrics(density: androidx.compose.ui.unit.Density): ArtifactTreeMetrics {
    return with(density) {
        ArtifactTreeMetrics(
            nodeWidth = NODE_CARD_WIDTH.toPx(),
            nodeHeight = NODE_CARD_HEIGHT.toPx(),
            columnGap = COLUMN_GAP.toPx(),
            rowGap = ROW_GAP.toPx(),
            padding = TREE_PADDING.toPx(),
            cornerRadius = NODE_CORNER_RADIUS.toPx(),
            borderWidth = NODE_BORDER_WIDTH.toPx(),
            edgeWidth = EDGE_WIDTH.toPx(),
            arrowSize = ARROW_SIZE.toPx(),
            contentPadding = NODE_CONTENT_PADDING.toPx(),
            lineGap = NODE_LINE_GAP.toPx()
        )
    }
}

private data class ArtifactProjectNodePlacement(
    val node: ArtifactProjectNodeResponse,
    val preferredSlot: Float?,
    val fallbackOrder: Float
)

private val NODE_CARD_WIDTH = 108.dp
private val NODE_CARD_HEIGHT = 72.dp
private val COLUMN_GAP = 30.dp
private val ROW_GAP = 42.dp
private val TREE_PADDING = 24.dp
private val NODE_CORNER_RADIUS = 16.dp
private val NODE_BORDER_WIDTH = 3.dp
private val EDGE_WIDTH = 5.dp
private val ARROW_SIZE = 10.dp
private val NODE_CONTENT_PADDING = 8.dp
private val NODE_LINE_GAP = 2.dp
private val VIEWPORT_FIT_PADDING = 20.dp
private const val MIN_CANVAS_SCALE = 0.35f
private const val MAX_CANVAS_SCALE = 2.8f
private const val TRANSFORM_TAP_SUPPRESSION_MS = 140L
