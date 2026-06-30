package com.ai.assistance.operit.ui.features.packages.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.api.GitHubIssue
import com.ai.assistance.operit.data.mcp.InstallProgress
import com.ai.assistance.operit.data.mcp.MCPRepository
import com.ai.assistance.operit.data.preferences.GitHubAuthPreferences
import com.ai.assistance.operit.ui.features.packages.market.UnifiedMarketDetailAction
import com.ai.assistance.operit.ui.features.packages.market.UnifiedMarketDetailBanner
import com.ai.assistance.operit.ui.features.packages.market.UnifiedMarketDetailCommentDialog
import com.ai.assistance.operit.ui.features.packages.market.UnifiedMarketDetailCommentsState
import com.ai.assistance.operit.ui.features.packages.market.UnifiedMarketDetailHeader
import com.ai.assistance.operit.ui.features.packages.market.UnifiedMarketDetailInfoRow
import com.ai.assistance.operit.ui.features.packages.market.UnifiedMarketDetailMetric
import com.ai.assistance.operit.ui.features.packages.market.UnifiedMarketDetailParticipant
import com.ai.assistance.operit.ui.features.packages.market.UnifiedMarketDetailReactionOption
import com.ai.assistance.operit.ui.features.packages.market.UnifiedMarketDetailReactionsState
import com.ai.assistance.operit.ui.features.packages.market.UnifiedMarketDetailScreen
import com.ai.assistance.operit.ui.features.packages.market.UnifiedMarketDetailSection
import com.ai.assistance.operit.ui.features.packages.market.buildMarketCommentReplyDraft
import com.ai.assistance.operit.ui.features.packages.market.formatMarketDetailCompactDate
import com.ai.assistance.operit.ui.features.packages.market.formatMarketDetailDate
import com.ai.assistance.operit.ui.features.packages.market.labelResId
import com.ai.assistance.operit.ui.features.packages.market.marketDetailInitial
import com.ai.assistance.operit.ui.features.packages.market.MarketReviewState
import com.ai.assistance.operit.ui.features.packages.market.resolveMcpReviewSnapshot
import com.ai.assistance.operit.ui.features.packages.market.resolveMcpMarketEntryId
import com.ai.assistance.operit.ui.features.packages.screens.mcp.viewmodel.MCPMarketViewModel
import com.ai.assistance.operit.ui.features.packages.utils.MCPPluginParser

@Composable
fun MCPPluginDetailScreen(
    issue: GitHubIssue,
    fromManage: Boolean = false,
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val mcpRepository = remember { MCPRepository(context.applicationContext) }
    val viewModel: MCPMarketViewModel =
        viewModel(factory = MCPMarketViewModel.Factory(context.applicationContext, mcpRepository))

    val githubAuth = remember { GitHubAuthPreferences.getInstance(context) }
    val currentUser by githubAuth.userInfoFlow.collectAsState(initial = null)

    val commentsMap by viewModel.issueComments.collectAsState()
    val isLoadingComments by viewModel.isLoadingComments.collectAsState()
    val isPostingComment by viewModel.isPostingComment.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val reactionsMap by viewModel.issueReactions.collectAsState()
    val isLoadingReactions by viewModel.isLoadingReactions.collectAsState()
    val isReacting by viewModel.isReacting.collectAsState()
    val userAvatarCache by viewModel.userAvatarCache.collectAsState()
    val repositoryCache by viewModel.repositoryCache.collectAsState()
    val marketStats by viewModel.marketStats.collectAsState()
    val installingPlugins by viewModel.installingPlugins.collectAsState()
    val installProgress by viewModel.installProgress.collectAsState()
    val installedPluginIds by viewModel.installedPluginIds.collectAsState()

    val pluginInfo = remember(issue) { MCPPluginParser.parsePluginInfo(issue) }
    val review = remember(issue) { issue.resolveMcpReviewSnapshot() }
    val pluginId = remember(pluginInfo.title) { pluginInfo.title.replace("[^a-zA-Z0-9_]".toRegex(), "_") }
    val entryId = remember(issue) { resolveMcpMarketEntryId(issue) }
    val currentComments = commentsMap[issue.number].orEmpty()
    val currentReactions = reactionsMap[issue.number].orEmpty()
    val repositoryInfo = repositoryCache[pluginInfo.repositoryUrl]
    val downloads = marketStats[entryId]?.downloads ?: 0
    val likes = if (currentReactions.isNotEmpty()) currentReactions.count { it.content == "+1" } else issue.reactions?.thumbs_up ?: 0
    val favorites = if (currentReactions.isNotEmpty()) currentReactions.count { it.content == "heart" } else issue.reactions?.heart ?: 0
    val authorAvatarUrl = repositoryInfo?.owner?.avatarUrl ?: userAvatarCache[pluginInfo.repositoryOwner]
    val currentUserLogin = currentUser?.login
    val hasThumbsUp = currentUserLogin != null && currentReactions.any { it.content == "+1" && it.user.login == currentUserLogin }
    val hasHeart = currentUserLogin != null && currentReactions.any { it.content == "heart" && it.user.login == currentUserLogin }
    val isInstalling = pluginId in installingPlugins
    val isInstalled = pluginId in installedPluginIds
    val currentProgress = installProgress[pluginId]
    val isPreviewMode = fromManage && issue.state == "open" && review.state != MarketReviewState.APPROVED
    val previewBannerContainerColor = androidx.compose.material3.MaterialTheme.colorScheme.tertiaryContainer
    val previewBannerContentColor = androidx.compose.material3.MaterialTheme.colorScheme.onTertiaryContainer

    var commentText by remember { mutableStateOf("") }
    var showCommentDialog by remember { mutableStateOf(false) }

    LaunchedEffect(issue.number, entryId) {
        viewModel.loadIssueComments(issue.number)
        viewModel.loadIssueReactions(issue.number)
        viewModel.ensureMarketStatsLoaded(entryId)
        if (pluginInfo.repositoryUrl.isNotBlank()) {
            viewModel.fetchRepositoryInfo(pluginInfo.repositoryUrl)
        }
        if (pluginInfo.repositoryOwner.isNotBlank()) {
            viewModel.fetchUserAvatar(pluginInfo.repositoryOwner)
        }
    }

    errorMessage?.let { error ->
        LaunchedEffect(error) {
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    val header =
        UnifiedMarketDetailHeader(
            title = issue.title,
            fallbackAvatarText = marketDetailInitial(issue.title),
            participants =
                listOf(
                    UnifiedMarketDetailParticipant(
                        roleLabel = stringResource(R.string.market_detail_author_role),
                        name = pluginInfo.repositoryOwner.ifBlank { stringResource(R.string.mcp_plugin_unknown_author) },
                        avatarUrl = authorAvatarUrl,
                        fallbackAvatarText = marketDetailInitial(pluginInfo.repositoryOwner.ifBlank { issue.title })
                    ),
                    UnifiedMarketDetailParticipant(
                        roleLabel = stringResource(R.string.market_detail_sharer_role),
                        name = issue.user.login,
                        avatarUrl = issue.user.avatarUrl,
                        fallbackAvatarText = marketDetailInitial(issue.user.login)
                    )
                ),
            badges = buildMcpBadges(pluginInfo),
            metrics =
                listOf(
                    UnifiedMarketDetailMetric(
                        value = downloads.toString(),
                        label = stringResource(R.string.market_sort_downloads)
                    ),
                    UnifiedMarketDetailMetric(
                        value = likes.toString(),
                        label = stringResource(R.string.market_sort_likes)
                    ),
                    UnifiedMarketDetailMetric(
                        value = formatMarketDetailCompactDate(issue.created_at),
                        label = stringResource(R.string.market_detail_published_label)
                    )
                ),
            statusLabel =
                if (issue.state == "open") {
                    stringResource(R.string.market_detail_status_available)
                } else {
                    stringResource(R.string.market_detail_status_closed)
                }
        )

    val sections =
        buildList {
            if (pluginInfo.description.isNotBlank()) {
                add(
                    UnifiedMarketDetailSection(
                        title = stringResource(R.string.market_detail_about_title),
                        body = pluginInfo.description,
                        icon = Icons.Default.Info,
                        showTitle = false
                    )
                )
            }
            if (pluginInfo.installConfig.isNotBlank()) {
                add(
                    UnifiedMarketDetailSection(
                        title = stringResource(R.string.install_config),
                        body = pluginInfo.installConfig,
                        icon = Icons.Default.Code,
                        isCodeBlock = true
                    )
                )
            }
        }

    val metadataRows =
        buildList {
            add(
                UnifiedMarketDetailInfoRow(
                    label = stringResource(R.string.type_label),
                    value = "MCP",
                    icon = Icons.Default.Info
                )
            )
            if (pluginInfo.version.isNotBlank()) {
                add(
                    UnifiedMarketDetailInfoRow(
                        label = stringResource(R.string.version_label),
                        value = pluginInfo.version,
                        icon = Icons.Default.Update
                    )
                )
            }
            if (pluginInfo.category.isNotBlank()) {
                add(
                    UnifiedMarketDetailInfoRow(
                        label = stringResource(R.string.market_detail_category_label),
                        value = pluginInfo.category,
                        icon = Icons.Default.Info
                    )
                )
            }
            if (pluginInfo.repositoryUrl.isNotBlank()) {
                add(
                    UnifiedMarketDetailInfoRow(
                        label = stringResource(R.string.mcp_plugin_repository),
                        value = pluginInfo.repositoryUrl,
                        icon = Icons.Default.Code
                    )
                )
            }
            add(
                UnifiedMarketDetailInfoRow(
                    label = stringResource(R.string.market_review_status_label),
                    value = stringResource(review.state.labelResId()),
                    icon = Icons.Default.Check
                )
            )
            if (review.reasons.isNotEmpty()) {
                add(
                    UnifiedMarketDetailInfoRow(
                        label = stringResource(R.string.market_review_reasons_label),
                        value = review.reasons.joinToString(separator = " / ") { reason ->
                            context.getString(reason.labelResId())
                        },
                        icon = Icons.Default.Info
                    )
                )
            }
            add(
                UnifiedMarketDetailInfoRow(
                    label = stringResource(R.string.market_detail_published_label),
                    value = formatMarketDetailDate(issue.created_at),
                    icon = Icons.Default.CalendarToday
                )
            )
            add(
                UnifiedMarketDetailInfoRow(
                    label = stringResource(R.string.updated_at_label),
                    value = formatMarketDetailDate(issue.updated_at),
                    icon = Icons.Default.Update
                )
            )
        }

    val reactionsState =
        UnifiedMarketDetailReactionsState(
            title = stringResource(R.string.mcp_plugin_community_feedback),
            helperText = if (currentUser == null) stringResource(R.string.mcp_plugin_login_required) else null,
            isLoading = issue.number in isLoadingReactions,
            isMutating = issue.number in isReacting,
            options =
                listOf(
                    UnifiedMarketDetailReactionOption(
                        label = stringResource(R.string.market_detail_like_action),
                        count = likes,
                        icon = Icons.Default.ThumbUp,
                        tint = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                        isSelected = hasThumbsUp,
                        enabled = currentUser != null,
                        onClick = {
                            if (!hasThumbsUp) {
                                viewModel.addReactionToIssue(issue.number, "+1")
                            }
                        }
                    ),
                    UnifiedMarketDetailReactionOption(
                        label = stringResource(R.string.market_detail_favorite_action),
                        count = favorites,
                        icon = Icons.Default.Favorite,
                        tint = Color(0xFFE91E63),
                        isSelected = hasHeart,
                        enabled = currentUser != null,
                        onClick = {
                            if (!hasHeart) {
                                viewModel.addReactionToIssue(issue.number, "heart")
                            }
                        }
                    )
                )
        )

    val commentsState =
        UnifiedMarketDetailCommentsState(
            title = stringResource(R.string.mcp_plugin_comments, currentComments.size),
            comments = currentComments,
            isLoading = issue.number in isLoadingComments,
            isPosting = issue.number in isPostingComment,
            canPost = currentUser != null,
            postHint = if (currentUser == null) stringResource(R.string.mcp_plugin_login_required) else null,
            onRefresh = { viewModel.loadIssueComments(issue.number) },
            onRequestPost = { showCommentDialog = true },
            onReplyToComment = { comment ->
                commentText = buildMarketCommentReplyDraft(comment)
                showCommentDialog = true
            }
        )

    val previewBanner =
        remember(isPreviewMode, review.state, review.reasons) {
            if (!isPreviewMode) {
                null
            } else {
                UnifiedMarketDetailBanner(
                    title = context.getString(R.string.market_detail_preview_title),
                    message = buildPreviewBannerMessage(context, review.state, review.reasons),
                    icon = Icons.Default.Warning,
                    containerColor = previewBannerContainerColor,
                    contentColor = previewBannerContentColor
                )
            }
        }

    UnifiedMarketDetailScreen(
        onNavigateBack = onNavigateBack,
        header = header,
        banner = previewBanner,
        primaryAction =
            UnifiedMarketDetailAction(
                label =
                    when {
                        isPreviewMode -> stringResource(R.string.market_detail_preview_action_label)
                        isInstalled -> stringResource(R.string.mcp_plugin_installed)
                        isInstalling -> mcpInstallLabel(context, currentProgress)
                        else -> stringResource(R.string.mcp_plugin_install)
                    },
                onClick = { viewModel.installMCPFromIssue(issue) },
                enabled = issue.state == "open" && !isPreviewMode && !isInstalled && !isInstalling,
                isLoading = isInstalling,
                icon =
                    when {
                        isPreviewMode -> Icons.Default.Warning
                        isInstalling -> null
                        isInstalled -> Icons.Default.Check
                        else -> Icons.Default.Download
                    }
            ),
        secondaryAction =
            if (pluginInfo.repositoryUrl.isNotBlank()) {
                UnifiedMarketDetailAction(
                    label = stringResource(R.string.mcp_plugin_repository),
                    onClick = { openExternalUrl(context, pluginInfo.repositoryUrl) },
                    icon = Icons.Default.Code
                )
            } else {
                null
            },
        sections = sections,
        metadataTitle = stringResource(R.string.metadata_title),
        metadataRows = metadataRows,
        reactions = reactionsState,
        comments = commentsState
    )

    if (showCommentDialog) {
        UnifiedMarketDetailCommentDialog(
            commentText = commentText,
            onCommentTextChange = { commentText = it },
            onDismiss = {
                showCommentDialog = false
                commentText = ""
            },
            onPost = {
                if (commentText.isNotBlank()) {
                    viewModel.postComment(issue.number, commentText)
                    showCommentDialog = false
                    commentText = ""
                }
            },
            isPosting = issue.number in isPostingComment
        )
    }
}

private fun buildMcpBadges(
    pluginInfo: MCPPluginParser.ParsedPluginInfo
): List<String> {
    return buildList {
        add("mcp")
        if (pluginInfo.category.isNotBlank()) {
            add(pluginInfo.category)
        }
        if (pluginInfo.version.isNotBlank()) {
            add("版本 ${normalizeDetailVersionBadge(pluginInfo.version)}")
        }
    }
}

private fun normalizeDetailVersionBadge(value: String): String {
    val trimmed = value.trim()
    return trimmed.removePrefix("v").removePrefix("V").ifBlank { trimmed }
}

private fun mcpInstallLabel(
    context: Context,
    progress: InstallProgress?
): String {
    return when (progress) {
        is InstallProgress.Downloading ->
            if (progress.progress >= 0) {
                context.getString(R.string.downloading_progress, "${progress.progress}%")
            } else {
                context.getString(R.string.downloading)
            }

        is InstallProgress.Extracting -> context.getString(R.string.extracting_progress)
        else -> context.getString(R.string.installing_progress)
    }
}

private fun openExternalUrl(
    context: Context,
    url: String
) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

private fun buildPreviewBannerMessage(
    context: Context,
    reviewState: MarketReviewState,
    reasons: List<com.ai.assistance.operit.ui.features.packages.market.MarketReviewReason>
): String {
    val baseMessage =
        when (reviewState) {
            MarketReviewState.PENDING -> context.getString(R.string.market_detail_preview_pending_message)
            MarketReviewState.APPROVED -> context.getString(R.string.market_detail_preview_approved_message)
            MarketReviewState.CHANGES_REQUESTED -> context.getString(R.string.market_detail_preview_changes_requested_message)
            MarketReviewState.REJECTED -> context.getString(R.string.market_detail_preview_rejected_message)
        }
    if (reasons.isEmpty() ||
        (reviewState != MarketReviewState.CHANGES_REQUESTED && reviewState != MarketReviewState.REJECTED)
    ) {
        return baseMessage
    }

    val reasonText =
        reasons.joinToString(separator = " / ") { reason ->
            context.getString(reason.labelResId())
        }
    return "$baseMessage\n${context.getString(R.string.market_review_reasons_label)}：$reasonText"
}
