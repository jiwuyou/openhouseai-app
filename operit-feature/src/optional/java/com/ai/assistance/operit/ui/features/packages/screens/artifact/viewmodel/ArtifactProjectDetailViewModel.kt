package com.ai.assistance.operit.ui.features.packages.screens.artifact.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ai.assistance.operit.BuildConfig
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.data.api.ArtifactProjectDetailResponse
import com.ai.assistance.operit.data.api.ArtifactProjectNodeResponse
import com.ai.assistance.operit.data.api.GitHubApiService
import com.ai.assistance.operit.data.api.GitHubComment
import com.ai.assistance.operit.data.api.GitHubReaction
import com.ai.assistance.operit.data.api.MarketStatsApiService
import com.ai.assistance.operit.data.preferences.GitHubAuthPreferences
import com.ai.assistance.operit.data.preferences.GitHubUser
import com.ai.assistance.operit.ui.features.packages.market.CommentPostSuccessBehavior
import com.ai.assistance.operit.ui.features.packages.market.GitHubIssueMarketService
import com.ai.assistance.operit.ui.features.packages.market.IssueInteractionController
import com.ai.assistance.operit.ui.features.packages.market.IssueInteractionMessages
import com.ai.assistance.operit.ui.features.packages.market.installArtifactProjectNode
import com.ai.assistance.operit.ui.features.packages.market.LocalArtifactInstallStateKind
import com.ai.assistance.operit.ui.features.packages.market.LocalInstalledArtifactSnapshot
import com.ai.assistance.operit.ui.features.packages.market.PublishArtifactType
import com.ai.assistance.operit.ui.features.packages.market.formatSupportedAppVersions
import com.ai.assistance.operit.ui.features.packages.market.getInstalledArtifactSnapshots
import com.ai.assistance.operit.ui.features.packages.market.isAppVersionSupported
import com.ai.assistance.operit.ui.features.packages.market.resolveLocalArtifactInstallState
import com.ai.assistance.operit.ui.features.packages.market.sameArtifactRuntimePackageId
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ArtifactProjectDetailViewModel(
    private val context: Context,
    private val projectId: String,
    private val initialNodeId: String? = null
) : ViewModel() {
    private val githubApiService = GitHubApiService(context)
    private val marketStatsApiService = MarketStatsApiService()
    private val githubAuth = GitHubAuthPreferences.getInstance(context)
    private val packageManager =
        PackageManager.getInstance(context, AIToolHandler.getInstance(context))
    private val marketServices =
        PublishArtifactType.entries.associateWith { type ->
            GitHubIssueMarketService(githubApiService, type.marketDefinition())
        }

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    private val scriptIssueInteractions =
        IssueInteractionController(
            scope = viewModelScope,
            context = context,
            marketService = marketServices.getValue(PublishArtifactType.SCRIPT),
            logTag = "$TAG-script",
            onError = { message -> _errorMessage.value = message },
            messages = artifactIssueInteractionMessages()
        )
    private val packageIssueInteractions =
        IssueInteractionController(
            scope = viewModelScope,
            context = context,
            marketService = marketServices.getValue(PublishArtifactType.PACKAGE),
            logTag = "$TAG-package",
            onError = { message -> _errorMessage.value = message },
            messages = artifactIssueInteractionMessages()
        )

    private val _project = MutableStateFlow<ArtifactProjectDetailResponse?>(null)
    val project: StateFlow<ArtifactProjectDetailResponse?> = _project.asStateFlow()

    private val _selectedNodeId = MutableStateFlow<String?>(null)

    val selectedNode: StateFlow<ArtifactProjectNodeResponse?> =
        combine(_project, _selectedNodeId) { project, selectedNodeId ->
            val nodes = project?.nodes.orEmpty()
            selectedNodeId?.let { nodeId ->
                nodes.firstOrNull { it.nodeId == nodeId }
            } ?: run {
                val projectValue = project ?: return@combine null
                nodes.firstOrNull { it.nodeId == projectValue.defaultNodeId } ?: nodes.firstOrNull()
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    private val _installingNodeIds = MutableStateFlow<Set<String>>(emptySet())
    val installingNodeIds: StateFlow<Set<String>> = _installingNodeIds.asStateFlow()

    private val _installedSnapshots = MutableStateFlow<Map<String, LocalInstalledArtifactSnapshot>>(emptyMap())
    private val _isRefreshingInstalledArtifacts = MutableStateFlow(true)
    val isRefreshingInstalledArtifacts: StateFlow<Boolean> = _isRefreshingInstalledArtifacts.asStateFlow()

    val isLoggedIn: StateFlow<Boolean> =
        githubAuth.isLoggedInFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val currentUser: StateFlow<GitHubUser?> =
        githubAuth.userInfoFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val issueComments: StateFlow<Map<Int, List<GitHubComment>>> =
        combine(_project, scriptIssueInteractions.issueComments, packageIssueInteractions.issueComments) { project, scriptComments, packageComments ->
            selectInteractionState(
                project = project,
                scriptValue = scriptComments,
                packageValue = packageComments,
                emptyValue = emptyMap()
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    val isLoadingComments: StateFlow<Set<Int>> =
        combine(_project, scriptIssueInteractions.isLoadingComments, packageIssueInteractions.isLoadingComments) { project, scriptLoading, packageLoading ->
            selectInteractionState(
                project = project,
                scriptValue = scriptLoading,
                packageValue = packageLoading,
                emptyValue = emptySet()
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptySet()
        )

    val isPostingComment: StateFlow<Set<Int>> =
        combine(_project, scriptIssueInteractions.isPostingComment, packageIssueInteractions.isPostingComment) { project, scriptPosting, packagePosting ->
            selectInteractionState(
                project = project,
                scriptValue = scriptPosting,
                packageValue = packagePosting,
                emptyValue = emptySet()
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptySet()
        )

    val issueReactions: StateFlow<Map<Int, List<GitHubReaction>>> =
        combine(_project, scriptIssueInteractions.issueReactions, packageIssueInteractions.issueReactions) { project, scriptReactions, packageReactions ->
            selectInteractionState(
                project = project,
                scriptValue = scriptReactions,
                packageValue = packageReactions,
                emptyValue = emptyMap()
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    val isLoadingReactions: StateFlow<Set<Int>> =
        combine(_project, scriptIssueInteractions.isLoadingReactions, packageIssueInteractions.isLoadingReactions) { project, scriptLoading, packageLoading ->
            selectInteractionState(
                project = project,
                scriptValue = scriptLoading,
                packageValue = packageLoading,
                emptyValue = emptySet()
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptySet()
        )

    val isReacting: StateFlow<Set<Int>> =
        combine(_project, scriptIssueInteractions.isReacting, packageIssueInteractions.isReacting) { project, scriptReacting, packageReacting ->
            selectInteractionState(
                project = project,
                scriptValue = scriptReacting,
                packageValue = packageReacting,
                emptyValue = emptySet()
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptySet()
        )

    init {
        loadProject()
        refreshInstalledArtifacts()
    }

    val currentAppVersion: String
        get() = BuildConfig.VERSION_NAME.trim().ifBlank { "unknown" }

    fun loadProject() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val projectDetail =
                    marketStatsApiService.getArtifactProject(projectId).getOrElse { error ->
                        throw error
                    }
                _project.value = projectDetail
                val preferredNodeId = initialNodeId?.trim().orEmpty()
                _selectedNodeId.value =
                    projectDetail.nodes.firstOrNull {
                        preferredNodeId.isNotBlank() && it.nodeId == preferredNodeId
                    }?.nodeId
                        ?: projectDetail.nodes.firstOrNull { it.nodeId == projectDetail.defaultNodeId }?.nodeId
                        ?: projectDetail.nodes.firstOrNull()?.nodeId
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to load artifact project"
                AppLogger.e(TAG, "Failed to load artifact project $projectId", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refreshInstalledArtifacts() {
        viewModelScope.launch {
            refreshInstalledArtifactsInternal()
        }
    }

    private suspend fun refreshInstalledArtifactsInternal() {
        _isRefreshingInstalledArtifacts.value = true
        try {
            _installedSnapshots.value =
                withContext(Dispatchers.IO) {
                    packageManager.getInstalledArtifactSnapshots()
                }
        } finally {
            _isRefreshingInstalledArtifacts.value = false
        }
    }

    fun installNode(node: ArtifactProjectNodeResponse) {
        viewModelScope.launch {
            _installingNodeIds.value = _installingNodeIds.value + node.nodeId
            _errorMessage.value = null
            try {
                installNodeInternal(node)
                refreshInstalledArtifactsInternal()
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to install artifact node"
                AppLogger.e(TAG, "Failed to install artifact node ${node.nodeId}", e)
            } finally {
                _installingNodeIds.value = _installingNodeIds.value - node.nodeId
            }
        }
    }

    fun loadIssueComments(issueNumber: Int) {
        currentIssueInteractionController()?.loadIssueComments(issueNumber, perPage = 50)
    }

    fun postIssueComment(
        issueNumber: Int,
        body: String
    ) {
        val text = body.trim()
        if (text.isBlank()) return

        viewModelScope.launch {
            if (!githubAuth.isLoggedIn()) {
                _errorMessage.value = "GitHub login required"
                return@launch
            }
            currentIssueInteractionController()?.postIssueComment(
                issueNumber = issueNumber,
                body = text,
                successBehavior = CommentPostSuccessBehavior.RELOAD_FROM_SERVER,
                perPage = 50
            )
        }
    }

    fun loadIssueReactions(
        issueNumber: Int,
        force: Boolean = false
    ) {
        currentIssueInteractionController()?.loadIssueReactions(issueNumber, force)
    }

    fun addReactionToIssue(
        issueNumber: Int,
        reactionType: String
    ) {
        viewModelScope.launch {
            if (!githubAuth.isLoggedIn()) {
                _errorMessage.value = "GitHub login required"
                return@launch
            }
            currentIssueInteractionController()?.addReactionToIssue(issueNumber, reactionType)
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun isCompatible(node: ArtifactProjectNodeResponse): Boolean {
        return runCatching {
            isAppVersionSupported(
                appVersion = currentAppVersion,
                minSupportedAppVersion = node.minSupportedAppVersion,
                maxSupportedAppVersion = node.maxSupportedAppVersion
            )
        }.getOrElse { error ->
            AppLogger.e(
                TAG,
                "Failed to evaluate compatibility for node=${node.nodeId}",
                error
            )
            false
        }
    }

    fun supportedVersionLabel(node: ArtifactProjectNodeResponse): String {
        return runCatching {
            formatSupportedAppVersions(
                minSupportedAppVersion = node.minSupportedAppVersion,
                maxSupportedAppVersion = node.maxSupportedAppVersion
            )
        }.getOrElse { error ->
            AppLogger.e(
                TAG,
                "Failed to format supported versions for node=${node.nodeId}",
                error
            )
            "Invalid"
        }
    }

    fun installState(node: ArtifactProjectNodeResponse): LocalArtifactInstallStateKind {
        return resolveLocalArtifactInstallState(
            installedSnapshots = _installedSnapshots.value,
            packageName = node.runtimePackageId,
            targetSha256 = node.sha256,
            projectNodeSha256s =
                _project.value
                    ?.nodes
                    .orEmpty()
                    .filter {
                        sameArtifactRuntimePackageId(it.runtimePackageId, node.runtimePackageId)
                    }
                    .map { it.sha256 }
        ).kind
    }

    private suspend fun installNodeInternal(node: ArtifactProjectNodeResponse) {
        installArtifactProjectNode(
            context = context,
            packageManager = packageManager,
            marketStatsApiService = marketStatsApiService,
            projectId = projectId,
            projectNodes = _project.value?.nodes.orEmpty(),
            node = node,
            logTag = TAG
        )
    }

    private fun currentIssueInteractionController(): IssueInteractionController? {
        return when (artifactTypeOf(_project.value)) {
            PublishArtifactType.SCRIPT -> scriptIssueInteractions
            PublishArtifactType.PACKAGE -> packageIssueInteractions
            null -> null
        }
    }

    private fun artifactTypeOf(project: ArtifactProjectDetailResponse?): PublishArtifactType? {
        return PublishArtifactType.fromWireValue(project?.type)
    }

    private fun <T> selectInteractionState(
        project: ArtifactProjectDetailResponse?,
        scriptValue: T,
        packageValue: T,
        emptyValue: T
    ): T {
        return when (artifactTypeOf(project)) {
            PublishArtifactType.SCRIPT -> scriptValue
            PublishArtifactType.PACKAGE -> packageValue
            null -> emptyValue
        }
    }

    private fun artifactIssueInteractionMessages(): IssueInteractionMessages {
        return IssueInteractionMessages(
            commentLoadFailed = { message -> "Failed to load comments: $message" },
            commentLoadError = { message -> "Failed to load comments: $message" },
            commentPostFailed = { message -> "Failed to post comment: $message" },
            commentPostError = { message -> "Failed to post comment: $message" },
            reactionFailed = { message -> "Failed to update reactions: $message" },
            reactionError = { message -> "Failed to update reactions: $message" }
        )
    }

    class Factory(
        private val context: Context,
        private val projectId: String,
        private val initialNodeId: String? = null
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ArtifactProjectDetailViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return ArtifactProjectDetailViewModel(context, projectId, initialNodeId) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    companion object {
        private const val TAG = "ArtifactProjectDetailVM"
    }
}
