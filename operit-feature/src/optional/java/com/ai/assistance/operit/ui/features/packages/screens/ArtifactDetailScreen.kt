package com.ai.assistance.operit.ui.features.packages.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.data.api.ArtifactProjectNodeResponse
import com.ai.assistance.operit.data.api.GitHubIssue
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.ui.features.packages.market.ArtifactPublishClusterContext
import com.ai.assistance.operit.ui.features.packages.market.LocalArtifactInstallStateKind
import com.ai.assistance.operit.ui.features.packages.market.MarketReviewState
import com.ai.assistance.operit.ui.features.packages.market.PluginCreationIntent
import com.ai.assistance.operit.ui.features.packages.market.PublishArtifactType
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
import com.ai.assistance.operit.ui.features.packages.market.resolveArtifactReviewSnapshot
import com.ai.assistance.operit.ui.features.packages.screens.artifact.viewmodel.ArtifactProjectDetailViewModel
import com.ai.assistance.operit.ui.features.packages.utils.ArtifactIssueParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ArtifactDetailEntryPoint {
    MARKET,
    MANAGE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactDetailScreen(
    issue: GitHubIssue,
    onNavigateBack: () -> Unit = {},
    onStartPluginCreation: (PluginCreationIntent) -> Unit = {},
    onContinuePublish: (ArtifactPublishClusterContext) -> Unit = {},
    entryPoint: ArtifactDetailEntryPoint = ArtifactDetailEntryPoint.MARKET
) {
    val info = remember(issue) { ArtifactIssueParser.parseArtifactInfo(issue) }
    val artifactType = info.type
    val projectId = info.projectId.ifBlank { info.normalizedId }
    val nodeId = info.nodeId.ifBlank { "legacy-${issue.id}" }
    if (artifactType == null || projectId.isBlank()) {
        InvalidArtifactMetadataScreen()
        return
    }

    val context = LocalContext.current
    val toolHandler = remember { AIToolHandler.getInstance(context) }
    val packageManager = remember { PackageManager.getInstance(context, toolHandler) }
    val scope = rememberCoroutineScope()
    val viewModel: ArtifactProjectDetailViewModel =
        viewModel(
            key = "artifact-detail-$projectId-$nodeId",
            factory =
                ArtifactProjectDetailViewModel.Factory(
                    context.applicationContext,
                    projectId,
                    nodeId
                )
        )

    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val project by viewModel.project.collectAsState()
    val selectedNode by viewModel.selectedNode.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val commentsMap by viewModel.issueComments.collectAsState()
    val isLoadingComments by viewModel.isLoadingComments.collectAsState()
    val isPostingComment by viewModel.isPostingComment.collectAsState()
    val reactionsMap by viewModel.issueReactions.collectAsState()
    val isLoadingReactions by viewModel.isLoadingReactions.collectAsState()
    val isReacting by viewModel.isReacting.collectAsState()
    val installingNodeIds by viewModel.installingNodeIds.collectAsState()
    val isRefreshingInstalledArtifacts by viewModel.isRefreshingInstalledArtifacts.collectAsState()
    val review = remember(issue) { issue.resolveArtifactReviewSnapshot() }

    val previewNode =
        remember(issue, info, entryPoint, review.state) {
            buildArtifactPreviewNode(
                issue = issue,
                info = info,
                entryPoint = entryPoint,
                reviewState = review.state
            )
        }
    val node = selectedNode ?: previewNode
    if (node == null) {
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.loading),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            InvalidArtifactMetadataScreen()
        }
        return
    }
    val isPreviewMode = previewNode != null && selectedNode == null

    val currentComments = commentsMap[node.issue.number].orEmpty()
    val currentReactions = reactionsMap[node.issue.number].orEmpty()
    val downloads = if (isPreviewMode) 0 else project?.downloads ?: 0
    val likes =
        if (currentReactions.isNotEmpty()) {
            currentReactions.count { it.content == "+1" }
        } else {
            node.issue.reactions?.thumbs_up ?: 0
        }
    val favorites =
        if (currentReactions.isNotEmpty()) {
            currentReactions.count { it.content == "heart" }
        } else {
            node.issue.reactions?.heart ?: 0
        }
    val currentUserLogin = currentUser?.login
    val hasThumbsUp =
        currentUserLogin != null &&
            currentReactions.any { it.content == "+1" && it.user.login == currentUserLogin }
    val hasHeart =
        currentUserLogin != null &&
            currentReactions.any { it.content == "heart" && it.user.login == currentUserLogin }
    val installState =
        if (isPreviewMode) {
            LocalArtifactInstallStateKind.NOT_INSTALLED
        } else {
            viewModel.installState(node)
        }
    val isInstalling = !isPreviewMode && installingNodeIds.contains(node.nodeId)
    val isCompatible = if (isPreviewMode) false else viewModel.isCompatible(node)
    val supportedVersionLabel =
        if (isPreviewMode) {
            formatPreviewSupportedVersionLabel(info)
        } else {
            viewModel.supportedVersionLabel(node)
        }

    var commentText by remember(node.issue.number) { mutableStateOf("") }
    var showCommentDialog by remember(node.issue.number) { mutableStateOf(false) }
    var showCompatibilityDialog by remember(node.issue.number) { mutableStateOf(false) }
    var showContinueDialog by remember(node.issue.number) { mutableStateOf(false) }
    var creationRequirement by remember(node.issue.number) { mutableStateOf("") }
    var creatorSetupRunning by remember(node.issue.number) { mutableStateOf(false) }
    var creatorSetupResult by remember(node.issue.number) { mutableStateOf<ToolResult?>(null) }

    LaunchedEffect(node.issue.number) {
        viewModel.loadIssueComments(node.issue.number)
        viewModel.loadIssueReactions(node.issue.number)
        viewModel.refreshInstalledArtifacts()
    }

    errorMessage?.takeIf { !isPreviewMode }?.let { error ->
        LaunchedEffect(error) {
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    val primaryActionUi =
        rememberArtifactPrimaryActionUi(
            issueState = node.issue.state,
            installState = installState,
            isInstalling = isInstalling,
            isRefreshingInstalledArtifacts = isRefreshingInstalledArtifacts,
            artifactType = artifactType,
            isPreviewMode = isPreviewMode
        )

    val header =
        UnifiedMarketDetailHeader(
            title = node.displayName.ifBlank { info.title },
            fallbackAvatarText = marketDetailInitial(node.displayName.ifBlank { info.title }),
            participants =
                listOf(
                    UnifiedMarketDetailParticipant(
                        roleLabel = stringResource(R.string.market_detail_publisher_role),
                        name = node.publisherLogin.ifBlank { node.issue.user.login },
                        avatarUrl = node.issue.user.avatarUrl,
                        fallbackAvatarText = marketDetailInitial(node.publisherLogin.ifBlank { node.issue.user.login })
                    ),
                    UnifiedMarketDetailParticipant(
                        roleLabel = stringResource(R.string.market_detail_sharer_role),
                        name = issue.user.login,
                        avatarUrl = issue.user.avatarUrl,
                        fallbackAvatarText = marketDetailInitial(issue.user.login)
                    )
                ),
            badges =
                buildArtifactBadges(
                    version = node.version,
                    artifactType = PublishArtifactType.fromWireValue(node.type) ?: artifactType,
                    supportedVersionLabel = supportedVersionLabel
                ),
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
                        value = formatMarketDetailCompactDate(node.issue.created_at),
                        label = stringResource(R.string.market_detail_published_label)
                    )
                ),
            statusLabel =
                if (node.issue.state == "open") {
                    stringResource(R.string.market_detail_status_available)
                } else {
                    stringResource(R.string.market_detail_status_closed)
                }
        )

    val sections =
        buildList {
            if (node.description.isNotBlank()) {
                add(
                    UnifiedMarketDetailSection(
                        title = stringResource(R.string.market_detail_about_title),
                        body = node.description,
                        icon = Icons.Default.Info,
                        showTitle = false
                    )
                )
            }
        }

    val metadataRows =
        buildList {
            add(
                UnifiedMarketDetailInfoRow(
                    label = stringResource(R.string.type_label),
                    value =
                        when (PublishArtifactType.fromWireValue(node.type) ?: artifactType) {
                            PublishArtifactType.PACKAGE -> stringResource(R.string.artifact_type_package)
                            PublishArtifactType.SCRIPT -> stringResource(R.string.artifact_type_script)
                        },
                    icon = Icons.Default.Tag
                )
            )
            add(
                UnifiedMarketDetailInfoRow(
                    label = stringResource(R.string.version_label),
                    value = node.version.ifBlank { "-" },
                    icon = Icons.Default.Update
                )
            )
            add(
                UnifiedMarketDetailInfoRow(
                    label = stringResource(R.string.supported_app_versions),
                    value = supportedVersionLabel,
                    icon = Icons.Default.Info
                )
            )
            add(
                UnifiedMarketDetailInfoRow(
                    label = stringResource(R.string.current_app_version_label),
                    value = viewModel.currentAppVersion,
                    icon = Icons.Default.Info
                )
            )
            addIfNotBlank(stringResource(R.string.artifact_detail_project_cluster), node.projectId, Icons.Default.Tag)
            addIfNotBlank(stringResource(R.string.artifact_detail_node_id), node.nodeId, Icons.Default.Tag)
            addIfNotBlank(stringResource(R.string.asset_file_label), node.assetName, Icons.Default.Info)
            addIfNotBlank(stringResource(R.string.release_tag_label), node.releaseTag, Icons.Default.Tag)
            addIfNotBlank(stringResource(R.string.sha256_label), node.sha256, Icons.Default.Info)
            addIfNotBlank(stringResource(R.string.source_file_label), node.sourceFileName, Icons.Default.Info)
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
                    value = formatMarketDetailDate(node.issue.created_at),
                    icon = Icons.Default.CalendarToday
                )
            )
            add(
                UnifiedMarketDetailInfoRow(
                    label = stringResource(R.string.updated_at_label),
                    value = formatMarketDetailDate(node.issue.updated_at),
                    icon = Icons.Default.Update
                )
            )
        }

    val reactionsState =
        UnifiedMarketDetailReactionsState(
            title = stringResource(R.string.mcp_plugin_community_feedback),
            helperText = if (currentUser == null) stringResource(R.string.mcp_plugin_login_required) else null,
            isLoading = node.issue.number in isLoadingReactions,
            isMutating = node.issue.number in isReacting,
            options =
                listOf(
                    UnifiedMarketDetailReactionOption(
                        label = stringResource(R.string.market_detail_like_action),
                        count = likes,
                        icon = Icons.Default.ThumbUp,
                        tint = MaterialTheme.colorScheme.primary,
                        isSelected = hasThumbsUp,
                        enabled = currentUser != null,
                        onClick = {
                            if (!hasThumbsUp) {
                                viewModel.addReactionToIssue(node.issue.number, "+1")
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
                                viewModel.addReactionToIssue(node.issue.number, "heart")
                            }
                        }
                    )
                )
        )

    val commentsState =
        UnifiedMarketDetailCommentsState(
            title = stringResource(R.string.comments_with_count, currentComments.size),
            comments = currentComments,
            isLoading = node.issue.number in isLoadingComments,
            isPosting = node.issue.number in isPostingComment,
            canPost = currentUser != null,
            postHint = if (currentUser == null) stringResource(R.string.mcp_plugin_login_required) else null,
            onRefresh = { viewModel.loadIssueComments(node.issue.number) },
            onRequestPost = { showCommentDialog = true },
            onReplyToComment = { comment ->
                commentText = buildMarketCommentReplyDraft(comment)
                showCommentDialog = true
            }
        )

    val detailBanner =
        buildArtifactPreviewBanner(
            isPreviewMode = isPreviewMode,
            reviewState = review.state,
            reasons = review.reasons
        ) ?: buildArtifactBanner(
            node = node,
            installState = installState,
            isCompatible = isCompatible,
            currentAppVersion = viewModel.currentAppVersion,
            supportedVersionLabel = supportedVersionLabel
        )

    UnifiedMarketDetailScreen(
        onNavigateBack = onNavigateBack,
        header = header,
        primaryAction =
            UnifiedMarketDetailAction(
                label = primaryActionUi.label,
                onClick = {
                    if (isCompatible) {
                        viewModel.installNode(node)
                    } else {
                        showCompatibilityDialog = true
                    }
                },
                enabled = primaryActionUi.enabled,
                isLoading = primaryActionUi.isLoading,
                icon = primaryActionUi.icon
            ),
        secondaryAction = null,
        banner = detailBanner,
        sections = sections,
        overviewExtraContent = {
            ArtifactNodeContinuePublishCard(
                node = node,
                onPublish = {
                    onContinuePublish(
                        ArtifactPublishClusterContext(
                            projectId = node.projectId,
                            rootNodeId = node.rootNodeId,
                            runtimePackageId = node.runtimePackageId,
                            parentNodeIds = listOf(node.nodeId),
                            lockedDisplayName = node.displayName.ifBlank { info.title },
                            projectDisplayName =
                                (project?.projectDisplayName ?: info.projectDisplayName)
                                    .ifBlank { node.projectDisplayName.ifBlank { info.title } },
                            projectDescription =
                                (project?.projectDescription ?: info.projectDescription)
                                    .ifBlank { node.projectDescription.ifBlank { node.description } }
                        )
                    )
                },
                onDevelop = { showContinueDialog = true }
            )
        },
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
                    viewModel.postIssueComment(node.issue.number, commentText)
                    showCommentDialog = false
                    commentText = ""
                }
            },
            isPosting = node.issue.number in isPostingComment
        )
    }

    if (showCompatibilityDialog) {
        AlertDialog(
            onDismissRequest = { showCompatibilityDialog = false },
            title = { Text(stringResource(R.string.unsupported_artifact_version_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.unsupported_artifact_version_message,
                        node.displayName.ifBlank { info.title },
                        viewModel.currentAppVersion,
                        supportedVersionLabel
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.installNode(node)
                        showCompatibilityDialog = false
                    }
                ) {
                    Text(stringResource(R.string.continue_download_anyway))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCompatibilityDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showContinueDialog) {
        AlertDialog(
            onDismissRequest = {
                showContinueDialog = false
                creationRequirement = ""
                creatorSetupRunning = false
                creatorSetupResult = null
            },
            title = { Text(stringResource(R.string.artifact_detail_continue_develop_current_node)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.artifact_detail_step_pull_skill_update),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        FilledTonalButton(
                            onClick = {
                                scope.launch {
                                    creatorSetupRunning = true
                                    creatorSetupResult = null
                                    creatorSetupResult =
                                        withContext(Dispatchers.IO) {
                                            runQuickPluginCreatorSetup(
                                                context = context,
                                                packageManager = packageManager,
                                                toolHandler = toolHandler
                                            )
                                        }
                                    creatorSetupRunning = false
                                }
                            },
                            enabled = !creatorSetupRunning,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (creatorSetupRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                            }
                            Text(stringResource(R.string.quick_plugin_creator_pull_skill_update))
                        }
                        creatorSetupResult?.let { result ->
                            Text(
                                text =
                                    if (result.success) {
                                        result.result.toString()
                                    } else {
                                        result.error ?: stringResource(R.string.unknown_error)
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color =
                                    if (result.success) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    }
                            )
                        }
                    }

                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.artifact_detail_step_switch_or_download_version),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        FilledTonalButton(
                            onClick = {
                                if (isCompatible) {
                                    viewModel.installNode(node)
                                } else {
                                    showCompatibilityDialog = true
                                }
                            },
                            enabled = primaryActionUi.enabled,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (primaryActionUi.isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                            } else if (primaryActionUi.icon != null) {
                                androidx.compose.material3.Icon(
                                    imageVector = primaryActionUi.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                            }
                            Text(primaryActionUi.label)
                        }
                    }

                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.artifact_detail_step_enter_requirement),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.artifact_detail_continue_develop_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = creationRequirement,
                            onValueChange = { creationRequirement = it },
                            label = { Text(stringResource(R.string.artifact_detail_ai_requirement_label)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val requirement = creationRequirement.trim()
                        if (requirement.isBlank()) {
                            return@TextButton
                        }
                        creationRequirement = ""
                        showContinueDialog = false
                        creatorSetupRunning = false
                        creatorSetupResult = null
                        onStartPluginCreation(
                            PluginCreationIntent.Continue(
                                runtimePackageId = node.runtimePackageId,
                                requirement = requirement
                            )
                        )
                    }
                ) {
                    Text(stringResource(R.string.artifact_detail_start_based_development))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showContinueDialog = false
                        creationRequirement = ""
                        creatorSetupRunning = false
                        creatorSetupResult = null
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun ArtifactNodeContinuePublishCard(
    node: ArtifactProjectNodeResponse,
    onPublish: () -> Unit,
    onDevelop: () -> Unit
) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.32f)
            )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.artifact_detail_update_based_on_version),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.artifact_detail_update_based_on_version_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onPublish,
                enabled = node.runtimePackageId.isNotBlank(),
                modifier = Modifier.height(38.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.Update,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(4.dp))
                Text(
                    text = stringResource(R.string.artifact_detail_publish_new_version),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp)
                )
            }
            Button(
                onClick = onDevelop,
                enabled = node.runtimePackageId.isNotBlank(),
                modifier = Modifier.height(38.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(4.dp))
                Text(
                    text = stringResource(R.string.artifact_detail_create_new_version),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp)
                )
            }
        }
    }
}

@Composable
private fun InvalidArtifactMetadataScreen(
    messageResId: Int = R.string.invalid_artifact_metadata
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(messageResId),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun buildArtifactBadges(
    version: String,
    artifactType: PublishArtifactType,
    supportedVersionLabel: String
): List<String> {
    return buildList {
        add(
            when (artifactType) {
                PublishArtifactType.PACKAGE -> "toolpkg"
                PublishArtifactType.SCRIPT -> "js"
            }
        )
        supportedVersionLabel
            .takeIf { it.isNotBlank() && !it.equals("Any", ignoreCase = true) }
            ?.let { add(stringResource(R.string.artifact_detail_badge_supported_version, it)) }
        if (version.isNotBlank()) {
            add(stringResource(R.string.artifact_detail_badge_version, normalizeDetailVersionBadge(version)))
        }
    }
}

@Composable
private fun buildArtifactBanner(
    node: ArtifactProjectNodeResponse,
    installState: LocalArtifactInstallStateKind,
    isCompatible: Boolean,
    currentAppVersion: String,
    supportedVersionLabel: String
): UnifiedMarketDetailBanner? {
    return when (installState) {
        LocalArtifactInstallStateKind.NAME_CONFLICT ->
            UnifiedMarketDetailBanner(
                title = stringResource(R.string.artifact_detail_name_conflict_title),
                message = stringResource(
                    R.string.artifact_detail_name_conflict_message,
                    node.runtimePackageId
                ),
                icon = Icons.Default.Warning,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )

        LocalArtifactInstallStateKind.BUILT_IN_CONFLICT ->
            UnifiedMarketDetailBanner(
                title = stringResource(R.string.artifact_detail_built_in_conflict_title),
                message = stringResource(
                    R.string.artifact_detail_built_in_conflict_message,
                    node.runtimePackageId
                ),
                icon = Icons.Default.Warning,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )

        else ->
            if (!isCompatible) {
                UnifiedMarketDetailBanner(
                    title = stringResource(R.string.unsupported_artifact_version_title),
                    message =
                        stringResource(
                            R.string.unsupported_artifact_version_message,
                            node.displayName.ifBlank { node.projectDisplayName },
                            currentAppVersion,
                            supportedVersionLabel
                        ),
                    icon = Icons.Default.Warning,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            } else {
                null
            }
    }
}

private fun normalizeDetailVersionBadge(value: String): String {
    val trimmed = value.trim()
    return trimmed.removePrefix("v").removePrefix("V").ifBlank { trimmed }
}

private fun buildArtifactPreviewNode(
    issue: GitHubIssue,
    info: ArtifactIssueParser.ParsedArtifactInfo,
    entryPoint: ArtifactDetailEntryPoint,
    reviewState: MarketReviewState
): ArtifactProjectNodeResponse? {
    if (entryPoint != ArtifactDetailEntryPoint.MANAGE) {
        return null
    }
    if (!issue.state.equals("open", ignoreCase = true)) {
        return null
    }
    if (info.type == null || info.projectId.isBlank()) {
        return null
    }

    return ArtifactProjectNodeResponse(
        projectId = info.projectId,
        type = info.type.wireValue,
        projectDisplayName = info.projectDisplayName.ifBlank { info.title },
        projectDescription = info.projectDescription.ifBlank { info.description },
        runtimePackageId = info.runtimePackageId,
        nodeId = info.nodeId.ifBlank { "preview-${issue.id}" },
        rootNodeId = info.rootNodeId.ifBlank { info.nodeId.ifBlank { "preview-${issue.id}" } },
        parentNodeIds = info.parentNodeIds,
        publisherLogin = info.publisherLogin.ifBlank { issue.user.login },
        releaseTag = info.releaseTag,
        assetName = info.assetName,
        downloadUrl = info.downloadUrl,
        sha256 = info.sha256,
        version = info.version,
        displayName = info.title,
        description = info.description,
        sourceFileName = info.sourceFileName,
        minSupportedAppVersion = info.minSupportedAppVersion,
        maxSupportedAppVersion = info.maxSupportedAppVersion,
        publishedAt = issue.created_at,
        state =
            when (reviewState) {
                MarketReviewState.REJECTED -> "closed"
                else -> issue.state
            },
        issue = issue
    )
}

private fun formatPreviewSupportedVersionLabel(
    info: ArtifactIssueParser.ParsedArtifactInfo
): String {
    val min = info.minSupportedAppVersion?.trim().orEmpty()
    val max = info.maxSupportedAppVersion?.trim().orEmpty()
    return when {
        min.isBlank() && max.isBlank() -> "Any"
        min.isNotBlank() && max.isNotBlank() -> "$min - $max"
        min.isNotBlank() -> "$min+"
        else -> "<= $max"
    }
}

@Composable
private fun buildArtifactPreviewBanner(
    isPreviewMode: Boolean,
    reviewState: MarketReviewState,
    reasons: List<com.ai.assistance.operit.ui.features.packages.market.MarketReviewReason>
): UnifiedMarketDetailBanner? {
    if (!isPreviewMode) {
        return null
    }

    return UnifiedMarketDetailBanner(
        title = stringResource(R.string.market_detail_preview_title),
        message = buildArtifactPreviewBannerMessage(reviewState, reasons),
        icon = Icons.Default.Warning,
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
    )
}

@Composable
private fun buildArtifactPreviewBannerMessage(
    reviewState: MarketReviewState,
    reasons: List<com.ai.assistance.operit.ui.features.packages.market.MarketReviewReason>
): String {
    val baseMessage =
        when (reviewState) {
            MarketReviewState.PENDING -> stringResource(R.string.market_detail_preview_pending_message)
            MarketReviewState.APPROVED -> stringResource(R.string.market_detail_preview_approved_message)
            MarketReviewState.CHANGES_REQUESTED -> stringResource(R.string.market_detail_preview_changes_requested_message)
            MarketReviewState.REJECTED -> stringResource(R.string.market_detail_preview_rejected_message)
        }
    if (reasons.isEmpty() ||
        (reviewState != MarketReviewState.CHANGES_REQUESTED && reviewState != MarketReviewState.REJECTED)
    ) {
        return baseMessage
    }

    val reasonLabels = mutableListOf<String>()
    for (reason in reasons) {
        reasonLabels += stringResource(reason.labelResId())
    }
    val reasonText = reasonLabels.joinToString(separator = " / ")
    return "$baseMessage\n${stringResource(R.string.market_review_reasons_label)}：$reasonText"
}

private data class ArtifactPrimaryActionUi(
    val label: String,
    val enabled: Boolean,
    val isLoading: Boolean,
    val icon: ImageVector?
)

@Composable
private fun rememberArtifactPrimaryActionUi(
    issueState: String,
    installState: LocalArtifactInstallStateKind,
    isInstalling: Boolean,
    isRefreshingInstalledArtifacts: Boolean,
    artifactType: PublishArtifactType,
    isPreviewMode: Boolean
): ArtifactPrimaryActionUi {
    val isBusy = isInstalling || isRefreshingInstalledArtifacts
    val label =
        when {
            isPreviewMode -> stringResource(R.string.market_detail_preview_action_label)
            isRefreshingInstalledArtifacts && !isInstalling -> stringResource(R.string.artifact_detail_checking_installation)
            isInstalling -> stringResource(R.string.downloading)
            installState == LocalArtifactInstallStateKind.EXACT_INSTALLED ->
                stringResource(R.string.installed)
            installState == LocalArtifactInstallStateKind.SAME_PROJECT_VARIANT_INSTALLED ->
                stringResource(R.string.artifact_detail_switch_to_this_version)
            installState == LocalArtifactInstallStateKind.NAME_CONFLICT ->
                stringResource(R.string.artifact_detail_name_conflict_title)
            installState == LocalArtifactInstallStateKind.BUILT_IN_CONFLICT ->
                stringResource(R.string.artifact_detail_built_in_conflict_title)
            artifactType == PublishArtifactType.SCRIPT -> stringResource(R.string.download_script)
            else -> stringResource(R.string.download_package)
        }

    return ArtifactPrimaryActionUi(
        label = label,
        enabled =
            !isPreviewMode &&
                issueState == "open" &&
                !isBusy &&
                installState !in
                    setOf(
                        LocalArtifactInstallStateKind.EXACT_INSTALLED,
                        LocalArtifactInstallStateKind.NAME_CONFLICT,
                        LocalArtifactInstallStateKind.BUILT_IN_CONFLICT
                    ),
        isLoading = isBusy,
        icon =
            when {
                isPreviewMode -> Icons.Default.Warning
                isBusy -> null
                installState == LocalArtifactInstallStateKind.EXACT_INSTALLED -> Icons.Default.Check
                installState == LocalArtifactInstallStateKind.SAME_PROJECT_VARIANT_INSTALLED -> Icons.Default.Update
                else -> Icons.Default.Download
            }
    )
}

private fun MutableList<UnifiedMarketDetailInfoRow>.addIfNotBlank(
    label: String,
    value: String,
    icon: ImageVector
) {
    if (value.isNotBlank()) {
        add(UnifiedMarketDetailInfoRow(label = label, value = value, icon = icon))
    }
}
