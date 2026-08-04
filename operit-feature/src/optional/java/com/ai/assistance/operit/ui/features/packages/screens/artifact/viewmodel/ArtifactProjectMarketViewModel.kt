package com.ai.assistance.operit.ui.features.packages.screens.artifact.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.data.api.ArtifactProjectDetailResponse
import com.ai.assistance.operit.data.api.ArtifactProjectNodeResponse
import com.ai.assistance.operit.data.api.ArtifactProjectRankEntryResponse
import com.ai.assistance.operit.data.api.GitHubIssue
import com.ai.assistance.operit.data.api.MarketStatsApiService
import com.ai.assistance.operit.ui.features.packages.market.ArtifactMarketScope
import com.ai.assistance.operit.ui.features.packages.market.GitHubIssueMarketService
import com.ai.assistance.operit.ui.features.packages.market.installArtifactProjectNode
import com.ai.assistance.operit.ui.features.packages.market.LocalArtifactInstallStateKind
import com.ai.assistance.operit.ui.features.packages.market.LocalInstalledArtifactSnapshot
import com.ai.assistance.operit.ui.features.packages.market.MARKET_REVIEW_STATUS_LABELS
import com.ai.assistance.operit.ui.features.packages.market.MarketSortOption
import com.ai.assistance.operit.ui.features.packages.market.PublishArtifactType
import com.ai.assistance.operit.ui.features.packages.market.getInstalledArtifactSnapshots
import com.ai.assistance.operit.ui.features.packages.market.resolveArtifactReviewSnapshot
import com.ai.assistance.operit.ui.features.packages.market.resolveLocalArtifactInstallState
import com.ai.assistance.operit.ui.features.packages.market.sameArtifactRuntimePackageId
import com.ai.assistance.operit.ui.features.packages.market.toMarketStatsType
import com.ai.assistance.operit.ui.features.packages.market.toRankMetric
import com.ai.assistance.operit.ui.features.packages.utils.ArtifactIssueParser
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ArtifactProjectMarketViewModel(
    private val context: Context,
    private val scope: ArtifactMarketScope
) : ViewModel() {
    private val marketStatsApiService = MarketStatsApiService()
    private val packageManager =
        PackageManager.getInstance(context, AIToolHandler.getInstance(context))
    private val marketServices =
        scope.supportedTypes().associateWith { type ->
            GitHubIssueMarketService(
                githubApiService = com.ai.assistance.operit.data.api.GitHubApiService(context),
                definition = type.marketDefinition()
            )
        }

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortOption = MutableStateFlow(MarketSortOption.UPDATED)
    val sortOption: StateFlow<MarketSortOption> = _sortOption.asStateFlow()

    private val _browseItems = MutableStateFlow<List<ArtifactProjectRankEntryResponse>>(emptyList())
    private val _searchItems = MutableStateFlow<List<ArtifactProjectRankEntryResponse>>(emptyList())

    val marketItems: StateFlow<List<ArtifactProjectRankEntryResponse>> =
        combine(_browseItems, _searchQuery, _searchItems, _sortOption) { browseItems, query, searchItems, sortOption ->
            val source = if (query.isBlank()) browseItems else searchItems
            sortItems(source, sortOption)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _installingIds = MutableStateFlow<Set<String>>(emptySet())
    val installingIds: StateFlow<Set<String>> = _installingIds.asStateFlow()
    private val _projectDetails = MutableStateFlow<Map<String, ArtifactProjectDetailResponse>>(emptyMap())
    private val _installedSnapshots = MutableStateFlow<Map<String, LocalInstalledArtifactSnapshot>>(emptyMap())

    val projectInstallStates: StateFlow<Map<String, LocalArtifactInstallStateKind>> =
        combine(marketItems, _projectDetails, _installedSnapshots) { items, projectDetails, installedSnapshots ->
            items.associate { item ->
                item.projectId to
                    resolveProjectInstallState(
                        item = item,
                        projectDetail = projectDetails[item.projectId],
                        installedSnapshots = installedSnapshots
                    )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    private val supportedTypes = scope.supportedTypes()
    private val currentBrowsePages = mutableMapOf<PublishArtifactType, Int>()
    private val totalBrowsePages = mutableMapOf<PublishArtifactType, Int>()
    private var searchJob: Job? = null

    fun loadMarketData() {
        viewModelScope.launch {
            _isLoading.value = true
            _isLoadingMore.value = false
            _errorMessage.value = null
            _hasMore.value = false
            currentBrowsePages.clear()
            totalBrowsePages.clear()
            try {
                loadBrowsePages(reset = true)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to load artifact market data"
                AppLogger.e(TAG, "Failed to load artifact market data", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadMoreMarketData() {
        if (_searchQuery.value.isNotBlank() || _isLoading.value || _isLoadingMore.value || !_hasMore.value) {
            return
        }

        viewModelScope.launch {
            _isLoadingMore.value = true
            try {
                loadBrowsePages(reset = false)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to load more artifact market data"
                AppLogger.e(TAG, "Failed to load more artifact market data", e)
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()

        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            _searchItems.value = emptyList()
            return
        }

        searchJob = viewModelScope.launch {
            delay(300)
            searchProjects(trimmed)
        }
    }

    fun onSortOptionChanged(option: MarketSortOption) {
        _sortOption.value = option
        loadMarketData()
    }

    fun installDefaultNode(item: ArtifactProjectRankEntryResponse) {
        viewModelScope.launch {
            _installingIds.value = _installingIds.value + item.projectId
            _errorMessage.value = null
            try {
                val project =
                    marketStatsApiService.getArtifactProject(item.projectId).getOrElse { error ->
                        throw error
                    }
                val defaultNode =
                    project.nodes.firstOrNull { it.nodeId == project.defaultNodeId }
                        ?: project.nodes.firstOrNull { it.nodeId == item.latestOpenNodeId }
                        ?: project.nodes.firstOrNull { it.nodeId == item.latestNodeId }
                        ?: throw IllegalStateException("Default artifact node not found")
                installNode(
                    projectId = item.projectId,
                    node = defaultNode,
                    projectNodes = project.nodes
                )
                _projectDetails.value = _projectDetails.value + (item.projectId to project)
                refreshInstalledArtifactsInternal()
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to install artifact"
                AppLogger.e(TAG, "Failed to install default artifact node for ${item.projectId}", e)
            } finally {
                _installingIds.value = _installingIds.value - item.projectId
            }
        }
    }

    fun openProject(
        projectId: String,
        onOpenSingleNode: (GitHubIssue) -> Unit,
        onOpenNodeTree: (ArtifactProjectDetailResponse) -> Unit,
        onOpenFailed: () -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                val projectDetail =
                    _projectDetails.value[projectId]
                        ?: marketStatsApiService.getArtifactProject(projectId).getOrElse { error ->
                            throw error
                        }.also { detail ->
                            _projectDetails.value = _projectDetails.value + (projectId to detail)
                        }

                val singleNodeIssue =
                    projectDetail
                        .nodes
                        .singleOrNull()
                        ?.issue
                if (singleNodeIssue != null) {
                    onOpenSingleNode(singleNodeIssue)
                } else {
                    onOpenNodeTree(projectDetail)
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to open artifact project"
                AppLogger.e(TAG, "Failed to open artifact project $projectId", e)
                onOpenFailed()
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun refreshInstalledArtifacts() {
        viewModelScope.launch {
            refreshInstalledArtifactsInternal()
        }
    }

    private suspend fun refreshInstalledArtifactsInternal() {
        _installedSnapshots.value =
            withContext(Dispatchers.IO) {
                packageManager.getInstalledArtifactSnapshots()
            }
    }

    private suspend fun searchProjects(query: String) {
        _isLoading.value = true
        _errorMessage.value = null
        try {
            val issues =
                aggregateResults { service ->
                    service.searchIssues(
                        rawQuery = query,
                        page = 1,
                        openOnly = true,
                        excludedLabels = MARKET_REVIEW_STATUS_LABELS.toList()
                    )
                }.getOrElse { error ->
                    throw error
                }
            val publiclyApprovedIssues =
                issues.filter { issue -> issue.resolveArtifactReviewSnapshot().isPubliclyApproved }
            val browseByProject = _browseItems.value.associateBy { it.projectId }
            val grouped = publiclyApprovedIssues.groupBy { issue ->
                val parsed = ArtifactIssueParser.parseArtifactInfo(issue)
                parsed.projectId.ifBlank { parsed.normalizedId }
            }
            _searchItems.value =
                grouped.entries
                    .mapNotNull { (projectId, nodes) ->
                        if (projectId.isBlank()) return@mapNotNull null
                        browseByProject[projectId] ?: buildSearchProjectEntry(projectId, nodes)
                    }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _errorMessage.value = e.message ?: "Failed to search artifact projects"
            _searchItems.value = emptyList()
            AppLogger.e(TAG, "Failed to search artifact projects", e)
        } finally {
            _isLoading.value = false
        }
    }

    private fun buildSearchProjectEntry(
        projectId: String,
        issues: List<com.ai.assistance.operit.data.api.GitHubIssue>
    ): ArtifactProjectRankEntryResponse? {
        val sorted = issues.sortedByDescending { it.created_at }
        val latestIssue = sorted.firstOrNull() ?: return null
        val latestOpenIssue = sorted.firstOrNull { it.state == "open" } ?: latestIssue
        val latestInfo = ArtifactIssueParser.parseArtifactInfo(latestIssue)
        val rootIssue = issues.minByOrNull { it.created_at } ?: latestIssue
        val likes = issues.sumOf { it.reactions?.thumbs_up ?: 0 }
        return ArtifactProjectRankEntryResponse(
            projectId = projectId,
            type = latestInfo.type?.wireValue.orEmpty(),
            projectDisplayName = latestInfo.projectDisplayName.ifBlank { latestInfo.title },
            projectDescription = latestInfo.projectDescription.ifBlank { latestInfo.description },
            rootPublisherLogin = ArtifactIssueParser.parseArtifactInfo(rootIssue).publisherLogin.ifBlank { rootIssue.user.login },
            downloads = 0,
            likes = likes,
            latestNodeId = latestInfo.nodeId,
            latestOpenNodeId = ArtifactIssueParser.parseArtifactInfo(latestOpenIssue).nodeId,
            defaultNodeId = ArtifactIssueParser.parseArtifactInfo(latestOpenIssue).nodeId,
            latestPublishedAt = latestOpenIssue.created_at.ifBlank { latestIssue.created_at }
        )
    }

    private suspend fun loadBrowsePages(reset: Boolean) {
        data class BrowsePageRequest(
            val type: PublishArtifactType,
            val nextPage: Int
        )

        data class BrowsePageResult(
            val request: BrowsePageRequest,
            val result: Result<com.ai.assistance.operit.data.api.ArtifactProjectRankPageResponse>
        )

        val requests =
            supportedTypes.mapNotNull { type ->
                val nextPage =
                    if (reset) {
                        1
                    } else {
                        val totalPages = totalBrowsePages[type] ?: Int.MAX_VALUE
                        val candidate = (currentBrowsePages[type] ?: 0) + 1
                        if (candidate > totalPages) return@mapNotNull null
                        candidate
                    }
                BrowsePageRequest(type = type, nextPage = nextPage)
            }

        val pageResults =
            coroutineScope {
                requests.map { request ->
                    async {
                        BrowsePageResult(
                            request = request,
                            result = marketStatsApiService.getArtifactRankPage(
                                type = request.type.toMarketStatsType().wireValue,
                                metric = _sortOption.value.toRankMetric(),
                                page = request.nextPage
                            )
                        )
                    }
                }.awaitAll()
            }

        val loadedItems = mutableListOf<ArtifactProjectRankEntryResponse>()
        var firstError: Throwable? = null

        pageResults.forEach { pageResult ->
            pageResult.result.fold(
                onSuccess = { page ->
                    currentBrowsePages[pageResult.request.type] = page.page
                    totalBrowsePages[pageResult.request.type] = page.totalPages.coerceAtLeast(1)
                    loadedItems += page.items
                },
                onFailure = { error ->
                    if (firstError == null) {
                        firstError = error
                    }
                    AppLogger.e(
                        TAG,
                        "Failed to load ${pageResult.request.type.wireValue} artifact project browse page ${pageResult.request.nextPage}",
                        error
                    )
                }
            )
        }

        if (loadedItems.isNotEmpty()) {
            val mergedItems =
                if (reset) {
                    loadedItems
                } else {
                    _browseItems.value + loadedItems
                }
            _browseItems.value = mergedItems.distinctBy { it.projectId }
        } else if (reset) {
            _browseItems.value = emptyList()
        }

        _hasMore.value =
            supportedTypes.any { type ->
                val currentPage = currentBrowsePages[type] ?: 0
                val totalPages = totalBrowsePages[type] ?: 0
                totalPages > 0 && currentPage < totalPages
            }

        firstError?.let { error ->
            _errorMessage.value = error.message ?: "Failed to load artifact market data"
        }
    }

    private suspend fun installNode(
        projectId: String,
        node: ArtifactProjectNodeResponse,
        projectNodes: List<ArtifactProjectNodeResponse>
    ) {
        installArtifactProjectNode(
            context = context,
            packageManager = packageManager,
            marketStatsApiService = marketStatsApiService,
            projectId = projectId,
            projectNodes = projectNodes,
            node = node,
            logTag = TAG
        )
    }

    private fun resolveProjectInstallState(
        item: ArtifactProjectRankEntryResponse,
        projectDetail: ArtifactProjectDetailResponse?,
        installedSnapshots: Map<String, LocalInstalledArtifactSnapshot>
    ): LocalArtifactInstallStateKind {
        val inlineDefaultNode =
            item.defaultNode
                ?.takeIf {
                    it.runtimePackageId.isNotBlank() && it.sha256.isNotBlank()
                }
        if (inlineDefaultNode != null) {
            val relatedHashes =
                item.runtimePackageNodeSha256s.filter { it.isNotBlank() }
                    .ifEmpty {
                        projectDetail
                            ?.nodes
                            .orEmpty()
                            .filter {
                                sameArtifactRuntimePackageId(
                                    it.runtimePackageId,
                                    inlineDefaultNode.runtimePackageId
                                )
                            }
                            .map { it.sha256 }
                    }
            return resolveLocalArtifactInstallState(
                installedSnapshots = installedSnapshots,
                packageName = inlineDefaultNode.runtimePackageId,
                targetSha256 = inlineDefaultNode.sha256,
                projectNodeSha256s = relatedHashes
            ).kind
        }

        val defaultNode =
            resolveDefaultNode(
                item = item,
                projectDetail = projectDetail
            ) ?: return LocalArtifactInstallStateKind.NOT_INSTALLED

        return resolveLocalArtifactInstallState(
            installedSnapshots = installedSnapshots,
            packageName = defaultNode.runtimePackageId,
            targetSha256 = defaultNode.sha256,
            projectNodeSha256s =
                projectDetail
                    ?.nodes
                    .orEmpty()
                    .filter {
                        sameArtifactRuntimePackageId(it.runtimePackageId, defaultNode.runtimePackageId)
                    }
                    .map { it.sha256 }
        ).kind
    }

    private fun resolveDefaultNode(
        item: ArtifactProjectRankEntryResponse,
        projectDetail: ArtifactProjectDetailResponse?
    ): ArtifactProjectNodeResponse? {
        val nodes = projectDetail?.nodes.orEmpty()
        return nodes.firstOrNull { it.nodeId == item.defaultNodeId }
            ?: nodes.firstOrNull { it.nodeId == projectDetail?.defaultNodeId }
            ?: nodes.firstOrNull { it.nodeId == item.latestOpenNodeId }
            ?: nodes.firstOrNull { it.nodeId == item.latestNodeId }
    }

    private suspend fun aggregateResults(
        request: suspend (GitHubIssueMarketService) -> Result<List<com.ai.assistance.operit.data.api.GitHubIssue>>
    ): Result<List<com.ai.assistance.operit.data.api.GitHubIssue>> {
        val results =
            coroutineScope {
                supportedTypes.map { type ->
                    async { request(marketServices.getValue(type)) }
                }.awaitAll()
            }

        val aggregated = mutableListOf<com.ai.assistance.operit.data.api.GitHubIssue>()
        results.forEach { result ->
            result.fold(
                onSuccess = { aggregated += it },
                onFailure = { return Result.failure(it) }
            )
        }
        return Result.success(
            aggregated
                .distinctBy { it.id }
                .sortedByDescending { it.created_at }
        )
    }

    private fun sortItems(
        items: List<ArtifactProjectRankEntryResponse>,
        sortOption: MarketSortOption
    ): List<ArtifactProjectRankEntryResponse> {
        return when (sortOption) {
            MarketSortOption.UPDATED,
            MarketSortOption.FEATURED ->
                items.sortedWith(
                    compareByDescending<ArtifactProjectRankEntryResponse> { it.latestPublishedAt.orEmpty() }
                        .thenBy { it.projectDisplayName.lowercase() }
                )

            MarketSortOption.DOWNLOADS ->
                items.sortedWith(
                    compareByDescending<ArtifactProjectRankEntryResponse> { it.downloads }
                        .thenByDescending { it.likes }
                        .thenByDescending { it.latestPublishedAt.orEmpty() }
                        .thenBy { it.projectDisplayName.lowercase() }
                )

            MarketSortOption.LIKES ->
                items.sortedWith(
                    compareByDescending<ArtifactProjectRankEntryResponse> { it.likes }
                        .thenByDescending { it.latestPublishedAt.orEmpty() }
                        .thenBy { it.projectDisplayName.lowercase() }
                )
        }
    }

    class Factory(
        private val context: Context,
        private val scope: ArtifactMarketScope
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ArtifactProjectMarketViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return ArtifactProjectMarketViewModel(context, scope) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    companion object {
        private const val TAG = "ArtifactProjectMarketVM"
    }
}
