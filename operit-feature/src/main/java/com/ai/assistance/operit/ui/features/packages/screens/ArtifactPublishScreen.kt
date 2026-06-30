package com.ai.assistance.operit.ui.features.packages.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.api.ArtifactProjectDetailResponse
import com.ai.assistance.operit.data.api.GitHubIssue
import com.ai.assistance.operit.data.api.MarketStatsApiService
import com.ai.assistance.operit.ui.features.packages.market.ArtifactMarketScope
import com.ai.assistance.operit.ui.features.packages.market.ArtifactPublishClusterContext
import com.ai.assistance.operit.ui.features.packages.market.PublishArtifactType
import com.ai.assistance.operit.ui.features.packages.market.PublishProgressStage
import com.ai.assistance.operit.ui.features.packages.market.sameArtifactRuntimePackageId
import com.ai.assistance.operit.ui.features.packages.screens.artifact.viewmodel.ArtifactMarketViewModel
import com.ai.assistance.operit.ui.features.packages.utils.ArtifactIssueParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactPublishScreen(
    onNavigateBack: () -> Unit,
    editingIssue: GitHubIssue? = null,
    publishContext: ArtifactPublishClusterContext? = null
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val isEditMode = editingIssue != null
    val marketStatsApiService = remember { MarketStatsApiService() }
    val viewModel: ArtifactMarketViewModel =
        viewModel(
            key = "artifact-publish-all",
            factory = ArtifactMarketViewModel.Factory(context.applicationContext, ArtifactMarketScope.ALL)
        )

    val artifacts by viewModel.publishableArtifacts.collectAsState()
    val publishStage by viewModel.publishProgressStage.collectAsState()
    val publishMessage by viewModel.publishMessage.collectAsState()
    val publishError by viewModel.publishErrorMessage.collectAsState()
    val publishSuccess by viewModel.publishSuccessMessage.collectAsState()
    val requiresForgeInitialization by viewModel.requiresForgeInitialization.collectAsState()
    val registrationRetryAvailable by viewModel.registrationRetryAvailable.collectAsState()
    val isLoggedIn by viewModel.isLoggedIn.collectAsState()

    val initialInfo = remember(editingIssue) { editingIssue?.let { ArtifactIssueParser.parseArtifactInfo(it) } }
    var mutablePublishContext by remember(isEditMode, publishContext) {
        mutableStateOf(if (isEditMode) null else publishContext)
    }
    val activePublishContext = if (isEditMode) null else mutablePublishContext
    val parentCount = activePublishContext?.parentNodeIds?.size ?: 0
    val isContinuationMode = activePublishContext != null
    val lockedRuntimePackageId = initialInfo?.runtimePackageId?.ifBlank { initialInfo.normalizedId }.orEmpty()
    val lockedDisplayName = activePublishContext?.lockedDisplayName?.trim().orEmpty()
    val isDisplayNameLocked = !isEditMode && lockedDisplayName.isNotBlank()
    val continuationSelectionTitle = stringResource(R.string.artifact_publish_selected_versions, parentCount)
    val continuationDescription =
        stringResource(R.string.artifact_publish_continuation_description)
    val loadVersionSelectionFailed = stringResource(R.string.artifact_publish_load_version_selection_failed)

    val filteredArtifacts =
        remember(artifacts, activePublishContext, isEditMode, lockedRuntimePackageId) {
            val runtimePackageId =
                if (isEditMode) {
                    lockedRuntimePackageId
                } else {
                    activePublishContext?.runtimePackageId
                }
            if (runtimePackageId.isNullOrBlank()) {
                artifacts
            } else {
                artifacts.filter {
                    sameArtifactRuntimePackageId(it.packageName, runtimePackageId)
                }
            }
        }

    var selectedPackageName by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable(initialInfo?.title, lockedDisplayName) {
        mutableStateOf(
            initialInfo?.title.orEmpty().ifBlank { lockedDisplayName }
        )
    }
    var description by rememberSaveable { mutableStateOf(initialInfo?.description.orEmpty()) }
    var version by rememberSaveable { mutableStateOf(initialInfo?.version.orEmpty().ifBlank { "1.0.0" }) }
    var minSupportedAppVersion by rememberSaveable { mutableStateOf(initialInfo?.minSupportedAppVersion.orEmpty()) }
    var maxSupportedAppVersion by rememberSaveable { mutableStateOf(initialInfo?.maxSupportedAppVersion.orEmpty()) }

    var selectorExpanded by remember { mutableStateOf(false) }
    var showConfirmationDialog by remember { mutableStateOf(false) }
    var showSecondForgeConfirm by remember { mutableStateOf(false) }
    var showSelectionDialog by remember { mutableStateOf(false) }
    var isSelectionProjectLoading by remember(activePublishContext?.projectId) { mutableStateOf(false) }
    var selectionProject by remember(activePublishContext?.projectId) { mutableStateOf<ArtifactProjectDetailResponse?>(null) }
    var selectionLoadError by remember(activePublishContext?.projectId) { mutableStateOf<String?>(null) }
    var pendingSelectedNodeIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(Unit) {
        viewModel.refreshPublishableArtifacts()
    }

    LaunchedEffect(filteredArtifacts, activePublishContext?.runtimePackageId, initialInfo?.normalizedId) {
        if (selectedPackageName.isBlank()) {
            val preferredRuntimePackageId =
                if (isEditMode) {
                    lockedRuntimePackageId.takeIf { it.isNotBlank() }
                } else {
                    activePublishContext?.runtimePackageId?.takeIf { it.isNotBlank() } ?: initialInfo?.normalizedId
                }
            val matched =
                filteredArtifacts.firstOrNull {
                    preferredRuntimePackageId != null &&
                        sameArtifactRuntimePackageId(it.packageName, preferredRuntimePackageId)
                } ?: filteredArtifacts.firstOrNull()
            if (matched != null) {
                selectedPackageName = matched.packageName
                if (!isEditMode && initialInfo == null) {
                    displayName = if (isDisplayNameLocked) lockedDisplayName else matched.displayName
                    description = matched.description
                    version = matched.inferredVersion ?: "1.0.0"
                }
            } else if (isEditMode && preferredRuntimePackageId != null) {
                selectedPackageName = preferredRuntimePackageId
            }
        }
    }

    val selectedArtifact = filteredArtifacts.firstOrNull { it.packageName == selectedPackageName }
    val selectedType = selectedArtifact?.type ?: initialInfo?.type
    val isPublishing = publishStage !in listOf(PublishProgressStage.IDLE, PublishProgressStage.COMPLETED)
    val selectorDisplayName =
        if (isEditMode) {
            selectedArtifact?.displayName
                ?: initialInfo?.title.orEmpty().ifBlank {
                    initialInfo?.sourceFileName.orEmpty().ifBlank { lockedRuntimePackageId }
                }
        } else {
            selectedArtifact?.displayName.orEmpty()
        }

    fun openSelectionEditor() {
        val publishContextValue = activePublishContext ?: return
        pendingSelectedNodeIds = publishContextValue.parentNodeIds.toSet()
        selectionLoadError = null
        val cachedProject = selectionProject
        if (cachedProject != null && cachedProject.projectId == publishContextValue.projectId) {
            showSelectionDialog = true
            return
        }
        isSelectionProjectLoading = true
        scope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    marketStatsApiService.getArtifactProject(publishContextValue.projectId)
                }
            isSelectionProjectLoading = false
            result.fold(
                onSuccess = { project ->
                    selectionProject = project
                    pendingSelectedNodeIds = pendingSelectedNodeIds.intersect(project.nodes.map { it.nodeId }.toSet())
                    showSelectionDialog = true
                },
                onFailure = { error ->
                    selectionLoadError = error.message ?: loadVersionSelectionFailed
                }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text =
                        when {
                            isEditMode -> stringResource(R.string.artifact_publish_edit_artifact_title)
                            isContinuationMode -> stringResource(R.string.artifact_publish_continue_on_version_title)
                            else -> stringResource(R.string.publish_description)
                        },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text =
                        when {
                            isEditMode -> stringResource(R.string.artifact_publish_edit_artifact_description)
                            isContinuationMode -> continuationDescription
                            else -> stringResource(R.string.artifact_publish_info_description)
                        },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (isEditMode && initialInfo != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val artifactTypeLabel =
                        when (initialInfo.type) {
                            PublishArtifactType.PACKAGE -> stringResource(R.string.artifact_type_package)
                            PublishArtifactType.SCRIPT -> stringResource(R.string.artifact_type_script)
                            null -> ""
                        }
                    val summaryText =
                        buildString {
                            if (artifactTypeLabel.isNotBlank()) {
                                append(artifactTypeLabel)
                            }
                            initialInfo.sourceFileName.takeIf { it.isNotBlank() }?.let {
                                if (isNotBlank()) append(" · ")
                                append(context.getString(R.string.artifact_publish_file_locked))
                            }
                        }.ifBlank { context.getString(R.string.artifact_publish_only_description_versions_editable) }
                    Text(
                        text = stringResource(R.string.artifact_publish_current_artifact),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(initialInfo.title.ifBlank { selectorDisplayName })
                    Text(
                        text = summaryText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (activePublishContext != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.32f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = continuationSelectionTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        TextButton(
                            onClick = ::openSelectionEditor,
                            enabled = !isSelectionProjectLoading
                        ) {
                            Text(
                                if (isSelectionProjectLoading) {
                                    stringResource(R.string.artifact_publish_loading_short)
                                } else {
                                    stringResource(R.string.change)
                                }
                            )
                        }
                    }
                    if (lockedDisplayName.isNotBlank()) {
                        Text(
                            text = stringResource(
                                R.string.artifact_publish_locked_plugin_name_hint,
                                lockedDisplayName
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = stringResource(R.string.artifact_publish_package_name_auto_inherited),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    selectionLoadError?.takeIf { it.isNotBlank() }?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        if (!isLoggedIn) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    stringResource(R.string.need_login_before_publish_artifact),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        if (!isEditMode && activePublishContext != null && filteredArtifacts.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    text = stringResource(R.string.artifact_publish_missing_local_continuation_artifact),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        if (isEditMode) {
            OutlinedTextField(
                value = selectorDisplayName,
                onValueChange = {},
                label = { Text(stringResource(R.string.local_artifact_entry)) },
                modifier = Modifier.fillMaxWidth(),
                readOnly = true,
                supportingText = {
                    selectedType?.let {
                        Text(
                            if (it == PublishArtifactType.PACKAGE) {
                                stringResource(R.string.publish_target_package_market)
                            } else {
                                stringResource(R.string.publish_target_script_market)
                            }
                        )
                    }
                }
            )
        } else {
            ExposedDropdownMenuBox(
                expanded = selectorExpanded,
                onExpandedChange = {
                    if (filteredArtifacts.isNotEmpty()) {
                        selectorExpanded = !selectorExpanded
                    }
                }
            ) {
                OutlinedTextField(
                    value = selectorDisplayName,
                    onValueChange = {},
                    label = { Text(stringResource(R.string.local_artifact_entry)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    readOnly = true,
                    enabled = filteredArtifacts.isNotEmpty(),
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = selectorExpanded)
                    },
                    supportingText = {
                        if (selectedType != null) {
                            Text(
                                text =
                                    if (selectedType == PublishArtifactType.PACKAGE) {
                                        stringResource(R.string.publish_target_package_market)
                                    } else {
                                        stringResource(R.string.publish_target_script_market)
                                    }
                            )
                        }
                    }
                )
                ExposedDropdownMenu(
                    expanded = selectorExpanded,
                    onDismissRequest = { selectorExpanded = false }
                ) {
                    filteredArtifacts.forEach { artifact ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(artifact.displayName)
                                    Text(
                                        text =
                                            if (artifact.type == PublishArtifactType.PACKAGE) {
                                                stringResource(R.string.artifact_type_package)
                                            } else {
                                                stringResource(R.string.artifact_type_script)
                                            },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                selectedPackageName = artifact.packageName
                                selectorExpanded = false
                                if (initialInfo == null) {
                                    displayName = if (isDisplayNameLocked) lockedDisplayName else artifact.displayName
                                    description = artifact.description
                                    version = artifact.inferredVersion ?: "1.0.0"
                                }
                            }
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = displayName,
            onValueChange = {
                if (!isEditMode && !isDisplayNameLocked) {
                    displayName = it
                }
            },
            label = { Text(stringResource(R.string.display_name_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            readOnly = isEditMode || isDisplayNameLocked,
            supportingText = {
                if (isEditMode) {
                    Text(stringResource(R.string.artifact_publish_published_name_readonly))
                } else if (isDisplayNameLocked) {
                    Text(stringResource(R.string.artifact_publish_locked_name_must_match_source))
                }
            }
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text(stringResource(R.string.description_label)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )
        OutlinedTextField(
            value = version,
            onValueChange = {
                if (!isEditMode) {
                    version = it
                }
            },
            label = { Text(stringResource(R.string.version_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            readOnly = isEditMode,
            supportingText = {
                if (isEditMode) {
                    Text(stringResource(R.string.artifact_publish_published_version_readonly))
                }
            }
        )
        OutlinedTextField(
            value = minSupportedAppVersion,
            onValueChange = { minSupportedAppVersion = it },
            label = { Text(stringResource(R.string.min_supported_app_version)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = { Text(stringResource(R.string.supported_version_input_hint)) }
        )
        OutlinedTextField(
            value = maxSupportedAppVersion,
            onValueChange = { maxSupportedAppVersion = it },
            label = { Text(stringResource(R.string.max_supported_app_version)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = { Text(stringResource(R.string.supported_version_input_hint)) }
        )

        publishError?.let { error ->
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.publish_failed_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (registrationRetryAvailable) {
                        OutlinedButton(onClick = viewModel::retryPendingMarketRegistration) {
                            Text(stringResource(R.string.retry_market_registration))
                        }
                    }
                }
            }
        }

        Button(
            onClick = { showConfirmationDialog = true },
            modifier = Modifier.fillMaxWidth(),
            enabled =
                isLoggedIn &&
                    displayName.isNotBlank() &&
                    description.isNotBlank() &&
                    !isPublishing &&
                    (
                        if (isEditMode) {
                            initialInfo?.type != null
                        } else {
                            selectedPackageName.isNotBlank() &&
                                (activePublishContext == null || filteredArtifacts.isNotEmpty())
                        }
                    )
        ) {
            if (isPublishing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                when {
                    isEditMode -> stringResource(R.string.artifact_publish_save_artifact_info)
                    isContinuationMode -> stringResource(R.string.artifact_publish_publish_update_version)
                    else -> stringResource(R.string.publish_to_market)
                }
            )
        }

        OutlinedButton(onClick = onNavigateBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.cancel))
        }
    }

    if (showSelectionDialog) {
        selectionProject?.let { project ->
            ArtifactProjectNodeTreeDialog(
                project = project,
                onDismissRequest = { showSelectionDialog = false },
                selectedNodeIds = pendingSelectedNodeIds,
                onToggleNodeSelection = { node ->
                    pendingSelectedNodeIds =
                        pendingSelectedNodeIds.toMutableSet().apply {
                            if (!add(node.nodeId)) {
                                remove(node.nodeId)
                            }
                        }
                },
                onConfirmSelection = {
                    val orderedSelectedNodeIds =
                        project.nodes
                            .map { it.nodeId }
                            .filter { it in pendingSelectedNodeIds }
                    if (orderedSelectedNodeIds.isNotEmpty()) {
                        mutablePublishContext =
                            activePublishContext?.copy(parentNodeIds = orderedSelectedNodeIds)
                        showSelectionDialog = false
                    }
                },
                confirmSelectionEnabled = pendingSelectedNodeIds.isNotEmpty()
            )
        }
    }

    if (publishMessage != null && isPublishing) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.publishing_progress)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = publishMessage.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                }
            },
            confirmButton = {}
        )
    }

    if (showConfirmationDialog && (selectedArtifact != null || isEditMode)) {
        AlertDialog(
            onDismissRequest = { showConfirmationDialog = false },
            title = {
                Text(
                    when {
                        isEditMode -> stringResource(R.string.artifact_publish_confirm_save_artifact_info)
                        isContinuationMode -> stringResource(R.string.artifact_publish_confirm_publish_update_version)
                        else -> stringResource(R.string.confirm_publish)
                    }
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isEditMode) {
                        Text(stringResource(R.string.artifact_publish_edit_confirmation_message))
                        Text(stringResource(R.string.description_colon, description))
                        Text(
                            stringResource(
                                R.string.supported_app_versions_colon,
                                minSupportedAppVersion.ifBlank { "-" },
                                maxSupportedAppVersion.ifBlank { "-" }
                            )
                        )
                    } else {
                        Text(
                            if (isContinuationMode) {
                                continuationSelectionTitle
                            } else {
                                stringResource(R.string.please_check_submitted_info)
                            }
                        )
                        if (isContinuationMode) {
                            Text(continuationDescription)
                        }
                        Text(stringResource(R.string.name_colon, displayName))
                        Text(stringResource(R.string.description_colon, description))
                        Text(stringResource(R.string.version_colon, version))
                        Text(
                            stringResource(
                                R.string.artifact_type_colon,
                                when (selectedType) {
                                    PublishArtifactType.PACKAGE -> stringResource(R.string.artifact_type_package)
                                    PublishArtifactType.SCRIPT -> stringResource(R.string.artifact_type_script)
                                    null -> "-"
                                }
                            )
                        )
                        Text(
                            stringResource(
                                R.string.supported_app_versions_colon,
                                minSupportedAppVersion.ifBlank { "-" },
                                maxSupportedAppVersion.ifBlank { "-" }
                            )
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmationDialog = false
                        if (isEditMode && editingIssue != null) {
                            viewModel.updatePublishedArtifact(
                                issue = editingIssue,
                                displayName = displayName,
                                description = description,
                                minSupportedAppVersion = minSupportedAppVersion.ifBlank { null },
                                maxSupportedAppVersion = maxSupportedAppVersion.ifBlank { null }
                            )
                        } else {
                            viewModel.requestPublish(
                                packageName = selectedPackageName,
                                displayName = displayName,
                                description = description,
                                version = version,
                                minSupportedAppVersion = minSupportedAppVersion.ifBlank { null },
                                maxSupportedAppVersion = maxSupportedAppVersion.ifBlank { null },
                                publishContext = activePublishContext
                            )
                        }
                    }
                ) {
                    Text(
                        when {
                            isEditMode -> stringResource(R.string.artifact_publish_confirm_save_artifact_info)
                            isContinuationMode -> stringResource(R.string.artifact_publish_confirm_publish_update_version)
                            else -> stringResource(R.string.confirm_publish)
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmationDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (requiresForgeInitialization && !showSecondForgeConfirm) {
        AlertDialog(
            onDismissRequest = {
                showSecondForgeConfirm = false
                viewModel.dismissForgeInitializationPrompt()
            },
            title = { Text(stringResource(R.string.create_operit_forge_title)) },
            text = { Text(stringResource(R.string.create_operit_forge_message)) },
            confirmButton = {
                TextButton(onClick = { showSecondForgeConfirm = true }) {
                    Text(stringResource(R.string.continue_action))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showSecondForgeConfirm = false
                        viewModel.dismissForgeInitializationPrompt()
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (requiresForgeInitialization && showSecondForgeConfirm) {
        AlertDialog(
            onDismissRequest = {
                showSecondForgeConfirm = false
                viewModel.dismissForgeInitializationPrompt()
            },
            title = { Text(stringResource(R.string.confirm_create_public_forge_title)) },
            text = { Text(stringResource(R.string.confirm_create_public_forge_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSecondForgeConfirm = false
                        viewModel.confirmForgeInitializationAndPublish()
                    }
                ) {
                    Text(stringResource(R.string.create_and_publish))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showSecondForgeConfirm = false
                        viewModel.dismissForgeInitializationPrompt()
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    publishSuccess?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.clearPublishMessages() },
            title = {
                Text(
                    when {
                        isEditMode -> stringResource(R.string.artifact_publish_artifact_info_updated_success)
                        isContinuationMode -> stringResource(R.string.artifact_publish_update_version_success)
                        else -> stringResource(R.string.publish_success)
                    }
                )
            },
            text = { Text(text = message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearPublishMessages()
                        onNavigateBack()
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            }
        )
    }
}
