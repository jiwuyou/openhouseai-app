package com.ai.assistance.operit.ui.features.packages.market

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.api.GitHubComment
import com.ai.assistance.operit.ui.main.LocalTopBarTitleContent
import com.ai.assistance.operit.ui.main.TopBarTitleContent
import com.ai.assistance.operit.ui.main.components.LocalAppBarContentColor
import com.ai.assistance.operit.ui.main.components.LocalIsCurrentScreen
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class UnifiedMarketDetailHeader(
    val title: String,
    val fallbackAvatarText: String,
    val participants: List<UnifiedMarketDetailParticipant> = emptyList(),
    val badges: List<String> = emptyList(),
    val metrics: List<UnifiedMarketDetailMetric> = emptyList(),
    val statusLabel: String? = null
)

data class UnifiedMarketDetailParticipant(
    val roleLabel: String,
    val name: String,
    val avatarUrl: String? = null,
    val fallbackAvatarText: String
)

data class UnifiedMarketDetailMetric(
    val value: String,
    val label: String
)

data class UnifiedMarketDetailAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val isLoading: Boolean = false,
    val icon: ImageVector? = null
)

data class UnifiedMarketDetailBanner(
    val title: String? = null,
    val message: String,
    val icon: ImageVector,
    val containerColor: Color? = null,
    val contentColor: Color? = null
)

data class UnifiedMarketDetailSection(
    val title: String,
    val body: String,
    val icon: ImageVector? = null,
    val isCodeBlock: Boolean = false,
    val showTitle: Boolean = true
)

data class UnifiedMarketDetailInfoRow(
    val label: String,
    val value: String,
    val icon: ImageVector? = null
)

data class UnifiedMarketDetailReactionOption(
    val label: String,
    val count: Int,
    val icon: ImageVector,
    val tint: Color,
    val isSelected: Boolean,
    val enabled: Boolean,
    val onClick: () -> Unit
)

data class UnifiedMarketDetailReactionsState(
    val title: String,
    val helperText: String? = null,
    val isLoading: Boolean = false,
    val isMutating: Boolean = false,
    val options: List<UnifiedMarketDetailReactionOption> = emptyList()
)

data class UnifiedMarketDetailCommentsState(
    val title: String,
    val comments: List<GitHubComment>,
    val isLoading: Boolean,
    val isPosting: Boolean,
    val canPost: Boolean,
    val postHint: String? = null,
    val onRefresh: () -> Unit,
    val onRequestPost: () -> Unit,
    val onReplyToComment: (GitHubComment) -> Unit = {}
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun UnifiedMarketDetailScreen(
    onNavigateBack: () -> Unit,
    header: UnifiedMarketDetailHeader,
    primaryAction: UnifiedMarketDetailAction,
    secondaryAction: UnifiedMarketDetailAction? = null,
    banner: UnifiedMarketDetailBanner? = null,
    sections: List<UnifiedMarketDetailSection> = emptyList(),
    overviewExtraContent: (@Composable () -> Unit)? = null,
    metadataTitle: String,
    metadataRows: List<UnifiedMarketDetailInfoRow>,
    reactions: UnifiedMarketDetailReactionsState?,
    comments: UnifiedMarketDetailCommentsState,
    modifier: Modifier = Modifier
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val tabHeaderIndex = 2
    val shouldPinTitle by remember {
        derivedStateOf { listState.firstVisibleItemIndex >= tabHeaderIndex }
    }

    BindUnifiedMarketDetailTopBarTitle(
        title = header.title,
        visible = shouldPinTitle
    )

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                UnifiedMarketDetailHeaderCard(header = header)
            }

            stickyHeader {
                UnifiedMarketDetailStickyTabs(
                    selectedTabIndex = selectedTabIndex,
                    commentsTitle = comments.title,
                    onTabSelected = { selectedTabIndex = it }
                )
            }

            if (selectedTabIndex == 0) {
                if (banner != null) {
                    item {
                        UnifiedMarketDetailBannerCard(banner = banner)
                    }
                }

                items(sections, key = { it.title }) { section ->
                    UnifiedMarketDetailSectionCard(section = section)
                }

                if (metadataRows.isNotEmpty()) {
                    item {
                        UnifiedMarketDetailMetadataCard(
                            title = metadataTitle,
                            rows = metadataRows
                        )
                    }
                }

                if (overviewExtraContent != null) {
                    item {
                        overviewExtraContent()
                    }
                }
            } else {
                item {
                    UnifiedMarketDetailCommentsSectionHeader(
                        state = comments,
                        reactions = reactions
                    )
                }

                if (comments.comments.isEmpty() && !comments.isLoading) {
                    item {
                        UnifiedMarketDetailEmptyCommentsCard()
                    }
                } else {
                    items(comments.comments, key = { it.id }) { comment ->
                        UnifiedMarketDetailCommentCard(
                            comment = comment,
                            onReply = { comments.onReplyToComment(comment) }
                        )
                    }
                }
            }
        }

        Surface(shadowElevation = 6.dp) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                UnifiedMarketDetailActionRow(
                    primaryAction = primaryAction,
                    secondaryAction = secondaryAction
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnifiedMarketDetailHeaderCard(
    header: UnifiedMarketDetailHeader
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            UnifiedMarketDetailLeadingIcon(
                title = header.title,
                fallbackAvatarText = header.fallbackAvatarText
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = header.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (header.badges.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        header.badges.forEach { badge ->
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            ) {
                                Text(
                                    text = badge,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        if (header.participants.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                header.participants.take(2).forEach { participant ->
                    UnifiedMarketDetailParticipantChip(
                        participant = participant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (header.metrics.isNotEmpty()) {
            UnifiedMarketDetailMetricsRow(metrics = header.metrics)
        }
    }
}

@Composable
private fun UnifiedMarketDetailStickyTabs(
    selectedTabIndex: Int,
    commentsTitle: String,
    onTabSelected: (Int) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val tabTitles =
                listOf(
                    stringResource(R.string.market_detail_about_title),
                    commentsTitle
                )

            TabRow(
                selectedTabIndex = selectedTabIndex,
                modifier = Modifier.widthIn(max = 240.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                divider = {},
                indicator = { tabPositions ->
                    if (selectedTabIndex < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                            height = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            ) {
                tabTitles.forEachIndexed { index, title ->
                    val selected = selectedTabIndex == index
                    Tab(
                        selected = selected,
                        onClick = { onTabSelected(index) },
                        modifier = Modifier.height(44.dp),
                        text = {
                            Box(
                                modifier = Modifier.padding(horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color =
                                        if (selected) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun UnifiedMarketDetailLeadingIcon(
    title: String,
    fallbackAvatarText: String
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
    ) {
        Box(
            modifier = Modifier.size(76.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = fallbackAvatarText.ifBlank { marketDetailInitial(title) },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun UnifiedMarketDetailParticipantChip(
    participant: UnifiedMarketDetailParticipant,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UnifiedMarketDetailSmallAvatar(
            avatarUrl = participant.avatarUrl,
            fallbackText = participant.fallbackAvatarText
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = participant.roleLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = participant.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun UnifiedMarketDetailSmallAvatar(
    avatarUrl: String?,
    fallbackText: String
) {
    if (!avatarUrl.isNullOrBlank()) {
        Image(
            painter = rememberAsyncImagePainter(avatarUrl),
            contentDescription = null,
            modifier =
                Modifier
                    .size(28.dp)
                    .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
        return
    }

    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = fallbackText.ifBlank { "?" },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun UnifiedMarketDetailMetricsRow(
    metrics: List<UnifiedMarketDetailMetric>
) {
    val displayedMetrics = metrics.take(4)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        displayedMetrics.forEachIndexed { index, metric ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = metric.value,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Clip
                )
                Text(
                    text = metric.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (index != displayedMetrics.lastIndex) {
                Box(
                    modifier =
                        Modifier
                            .padding(horizontal = 4.dp)
                            .size(width = 1.dp, height = 26.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f))
                )
            }
        }
    }
}

@Composable
private fun UnifiedMarketDetailActionRow(
    primaryAction: UnifiedMarketDetailAction,
    secondaryAction: UnifiedMarketDetailAction?,
    compact: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)
    ) {
        UnifiedMarketDetailPrimaryButton(
            action = primaryAction,
            modifier = Modifier.weight(1f),
            compact = compact
        )

        if (secondaryAction != null) {
            UnifiedMarketDetailSecondaryButton(
                action = secondaryAction,
                modifier = Modifier.weight(1f),
                compact = compact
            )
        }
    }
}

@Composable
private fun UnifiedMarketDetailPrimaryButton(
    action: UnifiedMarketDetailAction,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Button(
        onClick = action.onClick,
        enabled = action.enabled,
        modifier = modifier.heightIn(min = if (compact) 36.dp else 42.dp),
        shape = RoundedCornerShape(999.dp),
        contentPadding =
            PaddingValues(
                horizontal = if (compact) 12.dp else 16.dp,
                vertical = if (compact) 6.dp else 8.dp
            )
    ) {
        val iconSize = if (compact) 16.dp else 18.dp

        if (action.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(iconSize),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else if (action.icon != null) {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                modifier = Modifier.size(iconSize)
            )
        }

        Spacer(modifier = Modifier.size(if (compact) 6.dp else 8.dp))
        Text(
            text = action.label,
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun UnifiedMarketDetailSecondaryButton(
    action: UnifiedMarketDetailAction,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    OutlinedButton(
        onClick = action.onClick,
        enabled = action.enabled,
        modifier = modifier.heightIn(min = if (compact) 36.dp else 42.dp),
        shape = RoundedCornerShape(999.dp),
        contentPadding =
            PaddingValues(
                horizontal = if (compact) 12.dp else 16.dp,
                vertical = if (compact) 6.dp else 8.dp
            )
    ) {
        val iconSize = if (compact) 16.dp else 18.dp

        if (action.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(iconSize),
                strokeWidth = 2.dp
            )
        } else if (action.icon != null) {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                modifier = Modifier.size(iconSize)
            )
        }

        Spacer(modifier = Modifier.size(if (compact) 6.dp else 8.dp))
        Text(
            text = action.label,
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun UnifiedMarketDetailBannerCard(
    banner: UnifiedMarketDetailBanner
) {
    val containerColor = banner.containerColor ?: MaterialTheme.colorScheme.secondaryContainer
    val contentColor = banner.contentColor ?: MaterialTheme.colorScheme.onSecondaryContainer

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = banner.icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!banner.title.isNullOrBlank()) {
                    Text(
                        text = banner.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = contentColor
                    )
                }
                Text(
                    text = banner.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor
                )
            }
        }
    }
}

@Composable
private fun UnifiedMarketDetailSectionCard(
    section: UnifiedMarketDetailSection
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (section.showTitle && section.title.isNotBlank()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (section.icon != null) {
                    Icon(
                        imageVector = section.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (section.isCodeBlock) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            ) {
                Text(
                    text = section.body,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        } else {
            Text(
                text = section.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    }
}

@Composable
private fun UnifiedMarketDetailMetadataCard(
    title: String,
    rows: List<UnifiedMarketDetailInfoRow>
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        rows.forEach { row ->
            UnifiedMarketDetailMetadataRow(row = row)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    }
}

@Composable
private fun UnifiedMarketDetailMetadataRow(
    row: UnifiedMarketDetailInfoRow
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (row.icon != null) {
            Icon(
                imageVector = row.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp).padding(top = 2.dp)
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = row.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = row.value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun UnifiedMarketDetailReactionsCard(
    state: UnifiedMarketDetailReactionsState
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = state.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (state.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            }
        }

        if (!state.helperText.isNullOrBlank()) {
            Text(
                text = state.helperText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            state.options.forEach { option ->
                val buttonColors =
                    if (option.isSelected) {
                        androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                            containerColor = option.tint.copy(alpha = 0.14f),
                            contentColor = option.tint
                        )
                    } else {
                        androidx.compose.material3.ButtonDefaults.filledTonalButtonColors()
                    }

                FilledTonalButton(
                    onClick = option.onClick,
                    enabled = option.enabled && !option.isSelected && !state.isMutating,
                    modifier = Modifier.weight(1f),
                    colors = buttonColors,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = option.icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = "${option.label} ${option.count}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    }
}

@Composable
private fun UnifiedMarketDetailCommentsSectionHeader(
    state: UnifiedMarketDetailCommentsState,
    reactions: UnifiedMarketDetailReactionsState?
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (reactions != null) {
            UnifiedMarketDetailReactionsCard(state = reactions)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.mcp_plugin_user_comments),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier =
                    Modifier.clickable(enabled = state.canPost && !state.isPosting) {
                        state.onRequestPost()
                    },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                }
                Icon(
                    imageVector = Icons.Default.AddComment,
                    contentDescription = stringResource(R.string.market_detail_post_comment),
                    modifier = Modifier.size(16.dp),
                    tint =
                        if (state.canPost && !state.isPosting) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        }
                )
                Text(
                    text = stringResource(R.string.market_detail_post_comment),
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (state.canPost && !state.isPosting) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        }
                )
            }
        }

        if (!state.postHint.isNullOrBlank()) {
            Text(
                text = state.postHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UnifiedMarketDetailEmptyCommentsCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.mcp_plugin_no_comments),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.mcp_plugin_be_first_comment),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UnifiedMarketDetailCommentCard(
    comment: GitHubComment,
    onReply: () -> Unit
) {
    val parsedComment = remember(comment.id, comment.body) {
        parseMarketCommentBody(comment.body)
    }
    var bodyExpanded by remember(comment.id) { mutableStateOf(false) }
    var bodyOverflowed by remember(comment.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Image(
                painter = rememberAsyncImagePainter(comment.user.avatarUrl),
                contentDescription = null,
                modifier =
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = comment.user.login,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = formatMarketDetailDate(comment.updated_at),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                parsedComment.quoteBlock?.let { quoteBlock ->
                    UnifiedMarketDetailCommentQuoteBlock(quoteBlock = quoteBlock)
                }

                if (parsedComment.body.isNotBlank()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = parsedComment.body,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines =
                                if (bodyExpanded) {
                                    Int.MAX_VALUE
                                } else {
                                    MARKET_COMMENT_BODY_COLLAPSED_LINES
                                },
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { result ->
                                bodyOverflowed = result.hasVisualOverflow
                            }
                        )
                        if (bodyOverflowed || bodyExpanded) {
                            Text(
                                text =
                                    if (bodyExpanded) {
                                        stringResource(R.string.common_collapse)
                                    } else {
                                        stringResource(R.string.common_expand)
                                    },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier =
                                    Modifier.clickable { bodyExpanded = !bodyExpanded }
                            )
                        }
                    }
                } else if (parsedComment.quoteBlock == null) {
                    Text(
                        text = comment.body,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = stringResource(R.string.reply),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onReply)
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
private fun UnifiedMarketDetailCommentQuoteBlock(
    quoteBlock: MarketCommentQuoteBlock
) {
    var expanded by remember(quoteBlock) { mutableStateOf(false) }
    val totalQuoteLines = quoteBlock.visibleLines.size + quoteBlock.hiddenLines.size
    val hasHiddenFloor = quoteBlock.hiddenLines.isNotEmpty()
    val shouldCollapse = totalQuoteLines > MARKET_COMMENT_QUOTE_COLLAPSED_LINES || hasHiddenFloor
    val displayLines =
        when {
            expanded || !shouldCollapse -> quoteBlock.visibleLines + quoteBlock.hiddenLines
            else -> quoteBlock.visibleLines.take(MARKET_COMMENT_QUOTE_COLLAPSED_LINES)
        }

    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .animateContentSize()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            displayLines.forEach { quoteLine ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier =
                            Modifier
                                .padding(top = 2.dp)
                                .size(width = 3.dp, height = 16.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    if (quoteLine.level == 1) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                    } else {
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                                    }
                                )
                    )
                    Text(
                        text = quoteLine.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = ((quoteLine.level - 1) * 10).dp)
                    )
                }
            }

            if (shouldCollapse) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!expanded) {
                        Text(
                            text = "...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    Text(
                        text =
                            if (expanded) {
                                stringResource(R.string.common_collapse)
                            } else if (hasHiddenFloor) {
                                stringResource(R.string.mcp_plugin_comment_view_thread)
                            } else {
                                stringResource(R.string.common_expand)
                            },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier =
                            Modifier.clickable { expanded = !expanded }
                    )
                }
            }
        }
    }
}

@Composable
fun UnifiedMarketDetailCommentDialog(
    commentText: String,
    onCommentTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onPost: () -> Unit,
    isPosting: Boolean
) {
    AlertDialog(
        onDismissRequest = { if (!isPosting) onDismiss() },
        title = { Text(text = stringResource(R.string.mcp_plugin_add_comment)) },
        text = {
            OutlinedTextField(
                value = commentText,
                onValueChange = onCommentTextChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(text = stringResource(R.string.mcp_plugin_comment_hint)) },
                minLines = 4,
                enabled = !isPosting
            )
        },
        confirmButton = {
            TextButton(
                onClick = onPost,
                enabled = commentText.isNotBlank() && !isPosting
            ) {
                if (isPosting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(text = stringResource(R.string.mcp_plugin_post_comment))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isPosting) {
                Text(text = stringResource(R.string.mcp_plugin_cancel))
            }
        }
    )
}

fun formatMarketDetailDate(raw: String): String {
    return try {
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        parser.timeZone = TimeZone.getTimeZone("UTC")
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        formatter.format(parser.parse(raw) ?: return raw.take(10))
    } catch (_: Exception) {
        raw.take(10)
    }
}

fun formatMarketDetailCompactDate(raw: String): String {
    return try {
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        parser.timeZone = TimeZone.getTimeZone("UTC")
        val formatter = SimpleDateFormat("yy/MM/dd", Locale.getDefault())
        formatter.format(parser.parse(raw) ?: return raw.take(10))
    } catch (_: Exception) {
        raw.take(10)
    }
}

fun marketDetailInitial(text: String): String {
    return text
        .trim()
        .firstOrNull()
        ?.uppercaseChar()
        ?.toString()
        ?: "?"
}

fun splitMarketTags(raw: String): List<String> {
    return raw
        .split(Regex("[,，\\n]+"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}

private const val MARKET_COMMENT_QUOTE_COLLAPSED_LINES = 3
private const val MARKET_COMMENT_BODY_COLLAPSED_LINES = 8

@Composable
private fun BindUnifiedMarketDetailTopBarTitle(
    title: String,
    visible: Boolean
) {
    val setTopBarTitleContent = LocalTopBarTitleContent.current
    val isCurrentScreen = LocalIsCurrentScreen.current
    val appBarContentColor = LocalAppBarContentColor.current
    val latestTitle by rememberUpdatedState(title)
    val latestVisible by rememberUpdatedState(visible)
    val latestContentColor by rememberUpdatedState(appBarContentColor)

    LaunchedEffect(isCurrentScreen, latestVisible, latestTitle, latestContentColor) {
        if (!isCurrentScreen) {
            return@LaunchedEffect
        }
        setTopBarTitleContent(
            if (latestVisible) {
                TopBarTitleContent {
                    Text(
                        text = latestTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = latestContentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                null
            }
        )
    }
}

private data class MarketCommentBody(
    val quoteBlock: MarketCommentQuoteBlock?,
    val body: String
)

private data class MarketCommentQuoteLine(
    val level: Int,
    val text: String
)

private data class MarketCommentQuoteBlock(
    val visibleLines: List<MarketCommentQuoteLine>,
    val hiddenLines: List<MarketCommentQuoteLine>
)

private fun parseMarketCommentBody(raw: String): MarketCommentBody {
    val lines = raw.replace("\r\n", "\n").split('\n')
    val quoteLines = mutableListOf<MarketCommentQuoteLine>()
    var quoteEnded = false
    var bodyStartIndex = lines.size

    for ((index, line) in lines.withIndex()) {
        val parsedQuoteLine = parseMarketQuoteLine(line)
        if (!quoteEnded && parsedQuoteLine != null) {
            quoteLines += parsedQuoteLine
            continue
        }

        if (quoteLines.isNotEmpty()) {
            quoteEnded = true
            bodyStartIndex = index
            break
        }
    }

    val body =
        if (quoteLines.isEmpty()) {
            raw.trim()
        } else {
            lines.drop(bodyStartIndex).joinToString("\n").trim()
        }

    if (quoteLines.isEmpty()) {
        return MarketCommentBody(quoteBlock = null, body = body)
    }

    val visibleLines = quoteLines.filter { it.level <= 2 }
    val hiddenLines = quoteLines.filter { it.level > 2 }
    return MarketCommentBody(
        quoteBlock = MarketCommentQuoteBlock(visibleLines = visibleLines, hiddenLines = hiddenLines),
        body = body
    )
}

private fun parseMarketQuoteLine(line: String): MarketCommentQuoteLine? {
    val trimmedStart = line.trimStart()
    if (!trimmedStart.startsWith(">")) {
        return null
    }

    var depth = 0
    while (depth < trimmedStart.length && trimmedStart[depth] == '>') {
        depth++
    }

    val text = trimmedStart.drop(depth).trimStart().ifBlank { " " }
    return MarketCommentQuoteLine(level = depth, text = text)
}

fun buildMarketCommentReplyDraft(comment: GitHubComment): String {
    val quotedBody =
        comment.body
            .replace("\r\n", "\n")
            .split('\n')
            .joinToString("\n") { line -> "> ${line.trimEnd()}" }
            .trimEnd()

    return buildString {
        if (quotedBody.isNotBlank()) {
            append(quotedBody)
            append("\n\n")
        } else {
            append("> @")
            append(comment.user.login)
            append("\n\n")
        }
    }
}
