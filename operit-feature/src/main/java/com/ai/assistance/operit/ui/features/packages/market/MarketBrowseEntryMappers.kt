package com.ai.assistance.operit.ui.features.packages.market

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.api.ArtifactProjectRankEntryResponse
import com.ai.assistance.operit.data.api.GitHubIssue
import com.ai.assistance.operit.data.mcp.InstallProgress

val ArtifactMarketBrowseConfig =
    MarketBrowseSectionConfig(
        searchPlaceholderRes = R.string.artifact_market_search_placeholder,
        headerTitleRes = R.string.available_artifacts_market,
        emptySearchTitleRes = R.string.no_matching_artifacts_found,
        emptyDefaultTitleRes = R.string.no_artifacts_available
    )

val SkillMarketBrowseConfig =
    MarketBrowseSectionConfig(
        searchPlaceholderRes = R.string.skill_market_search_placeholder,
        headerTitleRes = R.string.available_skills_market,
        emptySearchTitleRes = R.string.no_matching_skills_found,
        emptyDefaultTitleRes = R.string.no_skills_available
    )

val McpMarketBrowseConfig =
    MarketBrowseSectionConfig(
        searchPlaceholderRes = R.string.mcp_market_search_hint,
        headerTitleRes = R.string.available_mcp_plugins,
        emptySearchTitleRes = R.string.no_matching_plugins_found,
        emptyDefaultTitleRes = R.string.no_mcp_plugins_available
    )

@Composable
fun rememberArtifactMarketBrowseEntry(
    item: ArtifactProjectRankEntryResponse,
    projectInstallStates: Map<String, LocalArtifactInstallStateKind>,
    installingIds: Set<String>,
    onViewDetails: (String) -> Unit,
    onInstallRequest: (ArtifactProjectRankEntryResponse) -> Unit
): MarketBrowseEntry {
    val isInstalling = item.projectId in installingIds
    val installState = projectInstallStates[item.projectId] ?: LocalArtifactInstallStateKind.NOT_INSTALLED

    return MarketBrowseEntry(
        model =
            MarketBrowseCardModel(
                title = item.projectDisplayName,
                description = truncateMarketBrowseDescription(item.projectDescription),
                ownerUsername = item.rootPublisherLogin,
                thumbsUpCount = item.likes,
                heartCount = 0,
                downloads = item.downloads,
                actionState =
                    if (isInstalling) {
                        MarketBrowseActionState.Installing()
                    } else {
                        when (installState) {
                            LocalArtifactInstallStateKind.EXACT_INSTALLED ->
                                MarketBrowseActionState.Installed

                            LocalArtifactInstallStateKind.SAME_PROJECT_VARIANT_INSTALLED ->
                                MarketBrowseActionState.Updatable

                            LocalArtifactInstallStateKind.NAME_CONFLICT,
                            LocalArtifactInstallStateKind.BUILT_IN_CONFLICT ->
                                MarketBrowseActionState.Unavailable(MarketUnavailableKind.Warning)

                            LocalArtifactInstallStateKind.NOT_INSTALLED ->
                                MarketBrowseActionState.Available
                        }
                    }
            ),
        onViewDetails = { onViewDetails(item.projectId) },
        onInstall = { onInstallRequest(item) }
    )
}

@Composable
fun rememberSkillMarketBrowseEntry(
    item: SkillMarketBrowseItem,
    marketStats: Map<String, MarketEntryStats>,
    installingSkills: Set<String>,
    installedSkillRepoUrls: Set<String>,
    installedSkillNames: Set<String>,
    onViewDetails: (GitHubIssue) -> Unit,
    onInstall: (SkillMarketBrowseItem) -> Unit
): MarketBrowseEntry {
    val context = LocalContext.current
    val repoUrl = item.repositoryUrl
    val entryId = item.entryId
    val isInstalling = repoUrl.isNotBlank() && repoUrl in installingSkills
    val isInstalled =
        (repoUrl.isNotBlank() && repoUrl in installedSkillRepoUrls) ||
            item.issue.title in installedSkillNames

    return MarketBrowseEntry(
        model =
            MarketBrowseCardModel(
                title = item.title,
                description = truncateMarketBrowseDescription(item.description),
                ownerUsername = item.ownerUsername,
                thumbsUpCount = item.issue.reactions?.thumbs_up ?: 0,
                heartCount = item.issue.reactions?.heart ?: 0,
                downloads = marketStats[entryId]?.downloads ?: 0,
                actionState =
                    when {
                        isInstalled -> MarketBrowseActionState.Installed
                        isInstalling -> MarketBrowseActionState.Installing()
                        item.issue.state == "open" -> MarketBrowseActionState.Available
                        else -> MarketBrowseActionState.Unavailable(MarketUnavailableKind.Info)
                    }
            ),
        onViewDetails = { onViewDetails(item.issue) },
        onInstall = {
            if (repoUrl.isBlank()) {
                Toast.makeText(
                    context,
                    context.getString(R.string.skill_repo_url_not_found_cannot_install),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                onInstall(item)
            }
        }
    )
}

@Composable
fun rememberMcpMarketBrowseEntry(
    item: McpMarketBrowseItem,
    marketStats: Map<String, MarketEntryStats>,
    installingPlugins: Set<String>,
    installProgress: Map<String, InstallProgress>,
    installedPluginIds: Set<String>,
    onViewDetails: (GitHubIssue) -> Unit,
    onInstall: (McpMarketBrowseItem) -> Unit
): MarketBrowseEntry {
    val pluginId = item.pluginId
    val entryId = item.entryId
    val isInstalling = pluginId in installingPlugins
    val isInstalled = pluginId in installedPluginIds
    val progress =
        when (val currentProgress = installProgress[pluginId]) {
            is InstallProgress.Downloading -> {
                currentProgress.progress.takeIf { it in 0..100 }?.div(100f)
            }

            else -> null
        }

    return MarketBrowseEntry(
        model =
            MarketBrowseCardModel(
                title = item.title,
                description = truncateMarketBrowseDescription(item.description),
                ownerUsername = item.ownerUsername,
                thumbsUpCount = item.issue.reactions?.thumbs_up ?: 0,
                heartCount = item.issue.reactions?.heart ?: 0,
                downloads = marketStats[entryId]?.downloads ?: 0,
                actionState =
                    when {
                        isInstalled -> MarketBrowseActionState.Installed
                        isInstalling -> MarketBrowseActionState.Installing(progress)
                        item.issue.state == "open" -> MarketBrowseActionState.Available
                        else -> MarketBrowseActionState.Unavailable(MarketUnavailableKind.Info)
                    }
            ),
        onViewDetails = { onViewDetails(item.issue) },
        onInstall = { onInstall(item) }
    )
}

private fun truncateMarketBrowseDescription(
    description: String,
    maxLength: Int = 100
): String {
    return if (description.length > maxLength) {
        description.take(maxLength) + "..."
    } else {
        description
    }
}
