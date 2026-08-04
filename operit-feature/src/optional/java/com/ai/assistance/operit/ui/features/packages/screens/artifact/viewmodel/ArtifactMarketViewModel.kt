package com.ai.assistance.operit.ui.features.packages.screens.artifact.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.data.api.MarketStatsApiService
import com.ai.assistance.operit.data.api.GitHubApiService
import com.ai.assistance.operit.data.api.GitHubIssue
import com.ai.assistance.operit.data.preferences.GitHubAuthPreferences
import com.ai.assistance.operit.ui.features.packages.market.ArtifactMarketScope
import com.ai.assistance.operit.ui.features.packages.market.collectArtifactPredecessorPublisherLogins
import com.ai.assistance.operit.ui.features.packages.market.ArtifactPublishClusterContext
import com.ai.assistance.operit.ui.features.packages.market.ForgeRepoInfo
import com.ai.assistance.operit.ui.features.packages.market.GitHubForgePublishService
import com.ai.assistance.operit.ui.features.packages.market.GitHubIssueMarketService
import com.ai.assistance.operit.ui.features.packages.market.inspectLocalArtifactAuthorDeclaration
import com.ai.assistance.operit.ui.features.packages.market.LocalPublishableArtifact
import com.ai.assistance.operit.ui.features.packages.market.MarketRegistrationPayload
import com.ai.assistance.operit.ui.features.packages.market.PublishArtifactRequest
import com.ai.assistance.operit.ui.features.packages.market.PublishArtifactType
import com.ai.assistance.operit.ui.features.packages.market.PublishAttemptResult
import com.ai.assistance.operit.ui.features.packages.market.PublishProgressStage
import com.ai.assistance.operit.ui.features.packages.market.buildArtifactMarketIssueBody
import com.ai.assistance.operit.ui.features.packages.market.formatSupportedAppVersions
import com.ai.assistance.operit.ui.features.packages.market.normalizeMarketArtifactId
import com.ai.assistance.operit.ui.features.packages.market.normalizeAppVersionOrNull
import com.ai.assistance.operit.ui.features.packages.market.sameArtifactRuntimePackageId
import com.ai.assistance.operit.ui.features.packages.market.validateStandaloneArtifactRuntimePackageId
import com.ai.assistance.operit.ui.features.packages.market.validateSupportedAppVersions
import com.ai.assistance.operit.ui.features.packages.utils.ArtifactIssueParser
import com.ai.assistance.operit.ui.features.github.GitHubOAuthCoordinator
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ArtifactMarketViewModel(
    private val context: Context,
    private val scope: ArtifactMarketScope
) : ViewModel() {
    data class MarketActionNotice(
        val title: String,
        val message: String
    )

    private val githubApiService = GitHubApiService(context)
    private val marketStatsApiService = MarketStatsApiService()
    private val githubAuth = GitHubAuthPreferences.getInstance(context)
    private val forgePublishService = GitHubForgePublishService(context, githubApiService)
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

    private val _publishableArtifacts = MutableStateFlow<List<LocalPublishableArtifact>>(emptyList())
    val publishableArtifacts: StateFlow<List<LocalPublishableArtifact>> = _publishableArtifacts.asStateFlow()

    private val _userPublishedArtifacts = MutableStateFlow<List<GitHubIssue>>(emptyList())
    val userPublishedArtifacts: StateFlow<List<GitHubIssue>> = _userPublishedArtifacts.asStateFlow()
    private val _hasLoadedUserPublishedArtifacts = MutableStateFlow(false)
    val hasLoadedUserPublishedArtifacts: StateFlow<Boolean> =
        _hasLoadedUserPublishedArtifacts.asStateFlow()

    private val _publishProgressStage = MutableStateFlow(PublishProgressStage.IDLE)
    val publishProgressStage: StateFlow<PublishProgressStage> = _publishProgressStage.asStateFlow()

    private val _publishMessage = MutableStateFlow<String?>(null)
    val publishMessage: StateFlow<String?> = _publishMessage.asStateFlow()

    private val _publishErrorMessage = MutableStateFlow<String?>(null)
    val publishErrorMessage: StateFlow<String?> = _publishErrorMessage.asStateFlow()

    private val _publishSuccessMessage = MutableStateFlow<String?>(null)
    val publishSuccessMessage: StateFlow<String?> = _publishSuccessMessage.asStateFlow()

    private val _marketActionNotice = MutableStateFlow<MarketActionNotice?>(null)
    val marketActionNotice: StateFlow<MarketActionNotice?> = _marketActionNotice.asStateFlow()

    private val _requiresForgeInitialization = MutableStateFlow(false)
    val requiresForgeInitialization: StateFlow<Boolean> = _requiresForgeInitialization.asStateFlow()

    private val _registrationRetryAvailable = MutableStateFlow(false)
    val registrationRetryAvailable: StateFlow<Boolean> = _registrationRetryAvailable.asStateFlow()

    val isLoggedIn: StateFlow<Boolean> =
        githubAuth.isLoggedInFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    private val supportedTypes = scope.supportedTypes()
    private var pendingPublishRequest: PublishArtifactRequest? = null
    private var pendingMarketRegistrationPayload: MarketRegistrationPayload? = null

    init {
        refreshPublishableArtifacts()
    }

    fun initiateGitHubLogin(context: Context) {
        viewModelScope.launch {
            try {
                val authUrl = GitHubOAuthCoordinator(context).createExternalAuthorizationUrl()
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to open GitHub login"
                AppLogger.e(TAG, "Failed to initiate GitHub login", e)
            }
        }
    }

    fun logoutFromGitHub() {
        viewModelScope.launch {
            try {
                githubAuth.logout()
                Toast.makeText(context, "Logged out from GitHub", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to logout from GitHub"
                AppLogger.e(TAG, "Failed to logout from GitHub", e)
            }
        }
    }

    fun refreshPublishableArtifacts() {
        viewModelScope.launch {
            val artifacts =
                withContext(Dispatchers.IO) {
                    packageManager.getPublishablePackageSources()
                        .mapNotNull { source ->
                            val type = inferArtifactType(source.isToolPkg, source.fileExtension) ?: return@mapNotNull null
                            if (type !in supportedTypes) return@mapNotNull null
                            val authorDeclaration = inspectLocalArtifactAuthorDeclaration(source)
                            LocalPublishableArtifact(
                                type = type,
                                packageName = source.packageName,
                                displayName = source.displayName,
                                description = source.description,
                                sourceFile = File(source.sourcePath),
                                hasDeclaredAuthorField = authorDeclaration.hasAuthorField,
                                declaredAuthorSlotCount = authorDeclaration.declaredAuthorSlotCount,
                                inferredVersion = source.inferredVersion
                            )
                        }
                        .sortedWith(compareBy<LocalPublishableArtifact> { it.type.ordinal }.thenBy { it.displayName.lowercase() })
            }
            _publishableArtifacts.value = artifacts
        }
    }

    fun loadUserPublishedArtifacts() {
        viewModelScope.launch {
            if (!githubAuth.isLoggedIn()) {
                _errorMessage.value = "GitHub login required"
                return@launch
            }

            _isLoading.value = true
            _errorMessage.value = null

            try {
                val userInfo = githubAuth.getCurrentUserInfo()
                if (userInfo == null) {
                    _errorMessage.value = "Unable to read GitHub user info"
                    return@launch
                }

                aggregateResults { service ->
                    service.getUserPublishedIssues(
                        creator = userInfo.login
                    )
                }.fold(
                    onSuccess = { issues ->
                        _userPublishedArtifacts.value = issues.sortedByDescending { it.updated_at }
                    },
                    onFailure = { error ->
                        _errorMessage.value = error.message ?: "Failed to load published artifacts"
                    }
                )
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to load published artifacts"
                AppLogger.e(TAG, "Failed to load user published artifacts", e)
            } finally {
                _hasLoadedUserPublishedArtifacts.value = true
                _isLoading.value = false
            }
        }
    }

    fun removeArtifactFromMarket(issue: GitHubIssue, title: String) {
        updateArtifactIssueState(
            issue = issue,
            state = "closed",
            successMessage = getText(R.string.artifact_market_remove_request_submitted_message, title)
        )
    }

    fun reopenArtifactInMarket(issue: GitHubIssue, title: String) {
        updateArtifactIssueState(
            issue = issue,
            state = "open",
            successMessage = getText(R.string.artifact_market_reopen_request_submitted_message, title)
        )
    }

    private fun updateArtifactIssueState(issue: GitHubIssue, state: String, successMessage: String) {
        viewModelScope.launch {
            if (!githubAuth.isLoggedIn()) {
                _errorMessage.value = "GitHub login required"
                return@launch
            }

            val type = ArtifactIssueParser.parseArtifactInfo(issue).type
            if (type == null) {
                _errorMessage.value = "Invalid artifact metadata"
                return@launch
            }

            _isLoading.value = true
            _errorMessage.value = null
            try {
                marketServices.getValue(type).updateIssueState(issue.number, state).fold(
                    onSuccess = {
                        _userPublishedArtifacts.value =
                            _userPublishedArtifacts.value.map { existing ->
                                if (existing.id == issue.id) existing.copy(state = state) else existing
                            }
                        _marketActionNotice.value =
                            MarketActionNotice(
                                title =
                                    if (state == "closed") {
                                        getText(R.string.artifact_market_remove_request_submitted_title)
                                    } else {
                                        getText(R.string.artifact_market_reopen_request_submitted_title)
                                    },
                                message = appendMarketScheduleNotice(successMessage)
                            )
                    },
                    onFailure = { error ->
                        _errorMessage.value = error.message ?: "Failed to update market entry"
                    }
                )
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to update market entry"
                AppLogger.e(TAG, "Failed to update market entry state", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun requestPublish(
        packageName: String,
        displayName: String,
        description: String,
        version: String,
        minSupportedAppVersion: String?,
        maxSupportedAppVersion: String?,
        publishContext: ArtifactPublishClusterContext? = null
    ) {
        val localArtifact = _publishableArtifacts.value.firstOrNull { it.packageName == packageName }
        if (localArtifact == null) {
            _publishErrorMessage.value = "Local artifact not found"
            return
        }

        val resolvedDisplayName =
            publishContext?.lockedDisplayName
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: displayName

        val request =
            PublishArtifactRequest(
                localArtifact = localArtifact,
                displayName = resolvedDisplayName,
                description = description,
                version = version,
                minSupportedAppVersion = minSupportedAppVersion,
                maxSupportedAppVersion = maxSupportedAppVersion,
                publishContext = publishContext
            )
        executePublish(request, allowCreateForgeRepo = false)
    }

    fun updatePublishedArtifact(
        issue: GitHubIssue,
        displayName: String,
        description: String,
        minSupportedAppVersion: String?,
        maxSupportedAppVersion: String?
    ) {
        viewModelScope.launch {
            if (!githubAuth.isLoggedIn()) {
                _publishErrorMessage.value = "GitHub login required"
                return@launch
            }

            _publishErrorMessage.value = null
            _publishSuccessMessage.value = null
            _publishProgressStage.value = PublishProgressStage.VALIDATING
            _publishMessage.value = getText(R.string.artifact_publish_updating_published_node)

            try {
                validateSupportedAppVersions(
                    minSupportedAppVersion = minSupportedAppVersion,
                    maxSupportedAppVersion = maxSupportedAppVersion
                )

                val type =
                    ArtifactIssueParser.parseArtifactInfo(issue).type
                        ?: throw IllegalStateException("Invalid artifact metadata")
                val payload =
                    buildUpdatedMarketRegistrationPayload(
                        issue = issue,
                        type = type,
                        displayName = displayName,
                        description = description,
                        minSupportedAppVersion = minSupportedAppVersion,
                        maxSupportedAppVersion = maxSupportedAppVersion
                    )
                ensureArtifactDisplayNameAvailable(
                    displayName = payload.displayName,
                    currentIssueId = issue.id
                )

                marketServices.getValue(type).updateIssueContent(
                    issueNumber = issue.number,
                    title = payload.displayName,
                    body = buildArtifactMarketIssueBody(payload)
                ).fold(
                    onSuccess = { updatedIssue ->
                        _publishProgressStage.value = PublishProgressStage.COMPLETED
                        _publishMessage.value = null
                        _publishSuccessMessage.value =
                            appendMarketScheduleNotice(
                                getText(R.string.artifact_publish_node_updated, payload.displayName)
                            )
                        _userPublishedArtifacts.value =
                            _userPublishedArtifacts.value.map { existing ->
                                if (existing.id == updatedIssue.id) updatedIssue else existing
                            }
                    },
                    onFailure = { error ->
                        _publishProgressStage.value = PublishProgressStage.IDLE
                        _publishMessage.value = null
                        _publishErrorMessage.value = error.message ?: "Failed to update artifact"
                    }
                )
            } catch (e: Exception) {
                _publishProgressStage.value = PublishProgressStage.IDLE
                _publishMessage.value = null
                _publishErrorMessage.value = e.message ?: "Failed to update artifact"
                AppLogger.e(TAG, "Failed to update published artifact", e)
            }
        }
    }

    fun confirmForgeInitializationAndPublish() {
        val request = pendingPublishRequest ?: return
        _requiresForgeInitialization.value = false
        executePublish(request, allowCreateForgeRepo = true)
    }

    fun dismissForgeInitializationPrompt() {
        pendingPublishRequest = null
        _requiresForgeInitialization.value = false
        _publishProgressStage.value = PublishProgressStage.IDLE
    }

    fun retryPendingMarketRegistration() {
        val payload = pendingMarketRegistrationPayload ?: return
        viewModelScope.launch {
            _publishProgressStage.value = PublishProgressStage.REGISTERING_MARKET
            _publishErrorMessage.value = null
            forgePublishService.retryMarketRegistration(payload).fold(
                onSuccess = {
                    _registrationRetryAvailable.value = false
                    pendingMarketRegistrationPayload = null
                    _publishProgressStage.value = PublishProgressStage.COMPLETED
                    _publishSuccessMessage.value =
                        appendMarketScheduleNotice(getText(R.string.artifact_market_registration_completed))
                    loadUserPublishedArtifacts()
                },
                onFailure = { error ->
                    _publishErrorMessage.value = error.message ?: "Failed to register market entry"
                    _publishProgressStage.value = PublishProgressStage.IDLE
                }
            )
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun resetUserPublishedArtifactsState() {
        _userPublishedArtifacts.value = emptyList()
        _hasLoadedUserPublishedArtifacts.value = false
        _errorMessage.value = null
        _marketActionNotice.value = null
    }

    fun clearPublishMessages() {
        _publishMessage.value = null
        _publishErrorMessage.value = null
        _publishSuccessMessage.value = null
        if (_publishProgressStage.value == PublishProgressStage.COMPLETED) {
            _publishProgressStage.value = PublishProgressStage.IDLE
        }
    }

    fun clearMarketActionNotice() {
        _marketActionNotice.value = null
    }

    private fun executePublish(request: PublishArtifactRequest, allowCreateForgeRepo: Boolean) {
        viewModelScope.launch {
            try {
                if (!githubAuth.isLoggedIn()) {
                    _publishErrorMessage.value = "GitHub login required"
                    return@launch
                }

                val resolvedDisplayName = resolvePublishDisplayName(request)
                val resolvedRequest =
                    if (resolvedDisplayName == request.displayName) {
                        request
                    } else {
                        request.copy(displayName = resolvedDisplayName)
                    }

                _publishErrorMessage.value = null
                _publishSuccessMessage.value = null
                _registrationRetryAvailable.value = false
                pendingMarketRegistrationPayload = null
                pendingPublishRequest = resolvedRequest
                _publishProgressStage.value = PublishProgressStage.VALIDATING
                _publishMessage.value = getText(R.string.artifact_publish_checking_identity_conflicts)

                if (resolvedRequest.publishContext == null) {
                    ensureFreshPublishIdentityAvailable(
                        displayName = resolvedRequest.displayName,
                        runtimePackageId = resolvedRequest.localArtifact.packageName
                    )
                }
                validateContinuationAuthorDeclaration(resolvedRequest)

                forgePublishService.publishArtifact(
                    request = resolvedRequest,
                    allowCreateForgeRepo = allowCreateForgeRepo,
                    onProgress = { stage ->
                        _publishProgressStage.value = stage
                        _publishMessage.value = stageMessage(stage)
                    }
                ).fold(
                    onSuccess = { result ->
                        when (result) {
                            is PublishAttemptResult.NeedsForgeInitialization -> {
                                _requiresForgeInitialization.value = true
                                _publishProgressStage.value = PublishProgressStage.IDLE
                                _publishMessage.value = null
                            }

                            is PublishAttemptResult.Success -> {
                                pendingPublishRequest = null
                                _publishProgressStage.value = PublishProgressStage.COMPLETED
                                _publishSuccessMessage.value = buildSuccessMessage(result.forgeRepo, result.payload)
                                loadUserPublishedArtifacts()
                            }

                            is PublishAttemptResult.RegistrationRetryRequired -> {
                                pendingMarketRegistrationPayload = result.payload
                                _registrationRetryAvailable.value = true
                                _publishProgressStage.value = PublishProgressStage.IDLE
                                _publishErrorMessage.value = result.errorMessage
                            }
                        }
                    },
                    onFailure = { error ->
                        _publishProgressStage.value = PublishProgressStage.IDLE
                        _publishMessage.value = null
                        _publishErrorMessage.value = formatPublishErrorMessage(error.message ?: "Publish failed")
                        AppLogger.e(TAG, "Failed to publish artifact", error)
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pendingPublishRequest = null
                _publishProgressStage.value = PublishProgressStage.IDLE
                _publishMessage.value = null
                _publishErrorMessage.value = e.message ?: "Failed to publish artifact"
                AppLogger.e(TAG, "Failed to publish artifact", e)
            }
        }
    }

    private suspend fun validateContinuationAuthorDeclaration(request: PublishArtifactRequest) {
        val publishContext = request.publishContext ?: return
        val parentNodeIds =
            publishContext.parentNodeIds
                .map(String::trim)
                .filter(String::isNotBlank)
        if (parentNodeIds.isEmpty()) {
            return
        }

        if (!request.localArtifact.hasDeclaredAuthorField) {
            return
        }

        val projectDetail =
            marketStatsApiService.getArtifactProject(publishContext.projectId).getOrElse { error ->
                throw error
            }
        val predecessorPublisherCount =
            collectArtifactPredecessorPublisherLogins(
                project = projectDetail,
                parentNodeIds = parentNodeIds
            ).size
        if (predecessorPublisherCount <= 0) {
            return
        }

        if (request.localArtifact.declaredAuthorSlotCount < predecessorPublisherCount) {
            throw IllegalStateException(
                getText(
                    R.string.artifact_publish_author_count_insufficient,
                    predecessorPublisherCount,
                    predecessorPublisherCount
                )
            )
        }
    }

    private suspend fun searchOpenIssuesByExactTitle(title: String): Result<List<GitHubIssue>> {
        return aggregateResults { service -> service.searchOpenIssuesByExactTitle(title, page = 1) }
    }

    private suspend fun ensureFreshPublishIdentityAvailable(
        displayName: String,
        runtimePackageId: String
    ) {
        validateStandaloneArtifactRuntimePackageId(runtimePackageId)

        val normalizedTitle = normalizePublishTitle(displayName)
        val normalizedRuntimePackageId = normalizeMarketArtifactId(runtimePackageId)
        val titleMatches =
            aggregateResults { service ->
                service.searchIssuesByExactTitle(
                    title = displayName,
                    page = 1,
                    openOnly = false
                )
            }.getOrElse { error ->
                val searchError = error.message ?: getText(R.string.github_search_failed)
                throw IllegalStateException(
                    getText(R.string.artifact_publish_check_name_duplicate_failed, searchError)
                )
            }
        val titleConflict =
            titleMatches.firstOrNull { existing ->
                val existingTitle =
                    ArtifactIssueParser.parseArtifactInfo(existing).title.ifBlank { existing.title }
                normalizePublishTitle(existingTitle) == normalizedTitle
            }

        val runtimeIdMatches =
            aggregateResults { service ->
                service.searchIssues(
                    rawQuery = "\"$runtimePackageId\"",
                    page = 1,
                    openOnly = false
                )
            }.getOrElse { error ->
                val searchError = error.message ?: getText(R.string.github_search_failed)
                throw IllegalStateException(
                    getText(R.string.artifact_publish_check_runtime_id_duplicate_failed, searchError)
                )
            }
        val runtimeIdConflict =
            runtimeIdMatches.firstOrNull { existing ->
                val parsed = ArtifactIssueParser.parseArtifactInfo(existing)
                val existingRuntimePackageId =
                    parsed.runtimePackageId.ifBlank { parsed.normalizedId }
                existingRuntimePackageId.isNotBlank() &&
                    sameArtifactRuntimePackageId(existingRuntimePackageId, runtimePackageId)
            }

        val normalizedIdConflict =
            if (normalizedRuntimePackageId.equals(runtimePackageId.trim(), ignoreCase = true)) {
                null
            } else {
                val normalizedIdMatches =
                    aggregateResults { service ->
                        service.searchIssues(
                            rawQuery = "\"$normalizedRuntimePackageId\"",
                            page = 1,
                            openOnly = false
                        )
                    }.getOrElse { error ->
                        val searchError = error.message ?: getText(R.string.github_search_failed)
                        throw IllegalStateException(
                            getText(
                                R.string.artifact_publish_check_normalized_id_duplicate_failed,
                                searchError
                            )
                        )
                    }
                normalizedIdMatches.firstOrNull { existing ->
                    val parsed = ArtifactIssueParser.parseArtifactInfo(existing)
                    val candidateIds =
                        listOf(
                            parsed.projectId,
                            parsed.runtimePackageId,
                            parsed.normalizedId
                        ).map(String::trim).filter(String::isNotBlank)
                    candidateIds.any { normalizeMarketArtifactId(it) == normalizedRuntimePackageId }
                }
            }

        if (titleConflict == null && runtimeIdConflict == null && normalizedIdConflict == null) {
            return
        }

        val conflictReasons = mutableListOf<String>()
        if (titleConflict != null) {
            conflictReasons += getText(R.string.artifact_publish_name_exists, displayName)
        }
        if (runtimeIdConflict != null) {
            conflictReasons += getText(R.string.artifact_publish_runtime_id_exists, runtimePackageId)
        }
        if (normalizedIdConflict != null) {
            conflictReasons += getText(
                R.string.artifact_publish_normalized_id_exists,
                normalizedRuntimePackageId
            )
        }

        throw IllegalStateException(
            getText(
                R.string.artifact_publish_identity_conflict_message,
                conflictReasons.joinToString(getText(R.string.comma_separator))
            )
        )
    }

    private suspend fun aggregateResults(
        request: suspend (GitHubIssueMarketService) -> Result<List<GitHubIssue>>
    ): Result<List<GitHubIssue>> {
        val results =
            coroutineScope {
                supportedTypes.map { type ->
                    async { request(marketServices.getValue(type)) }
                }.awaitAll()
            }

        val aggregated = mutableListOf<GitHubIssue>()
        results.forEach { result ->
            result.fold(
                onSuccess = { aggregated += it },
                onFailure = { return Result.failure(it) }
            )
        }
        return Result.success(
            aggregated
                .distinctBy { it.id }
                .sortedByDescending { it.updated_at }
        )
    }

    private fun stageMessage(stage: PublishProgressStage): String? {
        return when (stage) {
            PublishProgressStage.IDLE -> null
            PublishProgressStage.VALIDATING -> getText(R.string.artifact_publish_stage_validating)
            PublishProgressStage.ENSURING_REPO -> getText(R.string.artifact_publish_stage_ensuring_repo)
            PublishProgressStage.CREATING_RELEASE -> getText(R.string.artifact_publish_stage_creating_release)
            PublishProgressStage.UPLOADING_ASSET -> getText(R.string.artifact_publish_stage_uploading_asset)
            PublishProgressStage.REGISTERING_MARKET -> getText(R.string.artifact_publish_stage_registering_market)
            PublishProgressStage.COMPLETED -> getText(R.string.artifact_publish_stage_completed)
        }
    }

    private fun buildSuccessMessage(forgeRepo: ForgeRepoInfo, payload: MarketRegistrationPayload): String {
        return appendMarketScheduleNotice(
            buildString {
                append(getText(R.string.artifact_publish_success_header, payload.displayName, forgeRepo.repoName))
                append(getText(R.string.artifact_publish_success_type, payload.type.wireValue))
                append(getText(R.string.artifact_publish_success_project, payload.projectId))
                append(getText(R.string.artifact_publish_success_node, payload.nodeId))
                append(getText(R.string.artifact_publish_success_release_tag, payload.releaseTag))
                append(getText(R.string.artifact_publish_success_asset, payload.assetName))
                append(
                    getText(
                        R.string.artifact_publish_success_supported_versions,
                        formatSupportedAppVersions(
                            payload.minSupportedAppVersion,
                            payload.maxSupportedAppVersion
                        )
                    )
                )
            }
        )
    }

    private fun buildUpdatedMarketRegistrationPayload(
        issue: GitHubIssue,
        type: PublishArtifactType,
        displayName: String,
        description: String,
        minSupportedAppVersion: String?,
        maxSupportedAppVersion: String?
    ): MarketRegistrationPayload {
        val info = ArtifactIssueParser.parseArtifactInfo(issue)
        val nodeId = info.nodeId.ifBlank { "legacy-${issue.id}" }
        val rootNodeId = info.rootNodeId.ifBlank { nodeId }
        val isRootNode = rootNodeId == nodeId
        val trimmedDisplayName = displayName.trim().ifBlank { info.title }
        val trimmedDescription = description.trim().ifBlank { info.description }

        return MarketRegistrationPayload(
            type = type,
            projectId = info.projectId.ifBlank { info.normalizedId },
            projectDisplayName =
                if (isRootNode) {
                    trimmedDisplayName
                } else {
                    info.projectDisplayName.ifBlank { info.title }
                },
            projectDescription =
                if (isRootNode) {
                    trimmedDescription
                } else {
                    info.projectDescription.ifBlank { info.description }
                },
            runtimePackageId = info.runtimePackageId.ifBlank { info.normalizedId },
            nodeId = nodeId,
            rootNodeId = rootNodeId,
            parentNodeIds = info.parentNodeIds,
            publisherLogin = info.publisherLogin.ifBlank { issue.user.login },
            forgeRepo = info.forgeRepo,
            releaseTag = info.releaseTag,
            assetName = info.assetName,
            downloadUrl = info.downloadUrl,
            sha256 = info.sha256,
            version = info.version.trim().removePrefix("v").removePrefix("V").ifBlank { "1.0.0" },
            displayName = trimmedDisplayName,
            description = trimmedDescription,
            sourceFileName = info.sourceFileName,
            minSupportedAppVersion = normalizeAppVersionOrNull(minSupportedAppVersion),
            maxSupportedAppVersion = normalizeAppVersionOrNull(maxSupportedAppVersion)
        )
    }

    private suspend fun ensureArtifactDisplayNameAvailable(
        displayName: String,
        currentIssueId: Long? = null,
        allowExistingOpenDuplicate: Boolean = false
    ) {
        val trimmedDisplayName = displayName.trim()
        if (trimmedDisplayName.isBlank()) {
            throw IllegalArgumentException(getText(R.string.artifact_publish_plugin_name_empty))
        }
        if (allowExistingOpenDuplicate) {
            return
        }

        val existingIssues =
            searchOpenIssuesByExactTitle(trimmedDisplayName).getOrElse { error ->
                val searchError = error.message ?: getText(R.string.github_search_failed)
                throw IllegalStateException(
                    getText(R.string.artifact_publish_check_name_conflict_failed, searchError)
                )
            }
        val normalizedTitle = normalizePublishTitle(trimmedDisplayName)
        val conflictingIssue =
            existingIssues.firstOrNull { existing ->
                existing.id != currentIssueId &&
                    normalizePublishTitle(existing.title) == normalizedTitle
            }
        if (conflictingIssue != null) {
            throw IllegalStateException(
                getText(R.string.artifact_publish_display_name_taken_message, trimmedDisplayName)
            )
        }
    }

    private fun resolvePublishDisplayName(request: PublishArtifactRequest): String {
        val lockedDisplayName = request.publishContext?.lockedDisplayName?.trim().orEmpty()
        if (request.publishContext != null) {
            if (lockedDisplayName.isBlank()) {
                throw IllegalStateException(getText(R.string.artifact_publish_locked_name_required))
            }
            return lockedDisplayName
        }
        return request.displayName.trim()
    }

    private fun normalizePublishTitle(title: String): String {
        return title.trim().replace(Regex("\\s+"), " ").lowercase()
    }

    private fun formatPublishErrorMessage(rawMessage: String): String {
        val message = rawMessage.trim()
        return when {
            message.contains("Repository is empty", ignoreCase = true) ->
                getText(R.string.artifact_publish_empty_repo_error)

            message.contains("Validation Failed", ignoreCase = true) ->
                getText(R.string.artifact_publish_validation_failed_error)

            message.startsWith("HTTP ", ignoreCase = true) ->
                message.lineSequence().firstOrNull()?.trim().orEmpty().ifBlank {
                    getText(R.string.publish_failed_title)
                }

            else -> message
        }
    }

    private fun appendMarketScheduleNotice(baseMessage: String): String {
        return buildString {
            append(baseMessage.trim())
            append(getText(R.string.artifact_market_schedule_notice))
        }
    }

    private fun getText(resId: Int, vararg formatArgs: Any): String {
        return context.getString(resId, *formatArgs)
    }

    private fun inferArtifactType(isToolPkg: Boolean, fileExtension: String): PublishArtifactType? {
        val normalizedExtension = fileExtension.lowercase()
        return when {
            isToolPkg && normalizedExtension == "toolpkg" -> PublishArtifactType.PACKAGE
            !isToolPkg && normalizedExtension != "toolpkg" -> PublishArtifactType.SCRIPT
            else -> null
        }
    }

    class Factory(
        private val context: Context,
        private val scope: ArtifactMarketScope
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ArtifactMarketViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return ArtifactMarketViewModel(context, scope) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    companion object {
        private const val TAG = "ArtifactMarketViewModel"
    }
}
