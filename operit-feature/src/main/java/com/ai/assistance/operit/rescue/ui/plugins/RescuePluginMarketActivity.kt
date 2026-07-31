package com.ai.assistance.operit.rescue.ui.plugins

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.application.OperitApplication
import com.ai.assistance.operit.rescue.plugins.InstalledRescuePlugin
import com.ai.assistance.operit.rescue.plugins.RescuePluginComment
import com.ai.assistance.operit.rescue.plugins.RescuePluginListing
import com.ai.assistance.operit.rescue.plugins.RescuePluginManager
import com.ai.assistance.operit.ui.common.OperitUtilityTheme
import kotlinx.coroutines.launch

class RescuePluginMarketActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OperitApplication.initializeUiProcess(applicationContext)
        val manager = RescuePluginManager.get(applicationContext)
        setContent {
            OperitUtilityTheme {
                RescuePluginMarketScreen(
                    manager = manager,
                    initialPluginId = intent.getStringExtra(EXTRA_PLUGIN_ID),
                    onClose = ::finish,
                )
            }
        }
    }

    companion object {
        private const val EXTRA_PLUGIN_ID = "com.wuxianpi.extra.RESCUE_PLUGIN_ID"

        fun createIntent(context: Context, pluginId: String? = null): Intent =
            Intent(context, RescuePluginMarketActivity::class.java).apply {
                pluginId?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_PLUGIN_ID, it) }
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RescuePluginMarketScreen(
    manager: RescuePluginManager,
    initialPluginId: String?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hubUrl by remember { mutableStateOf(manager.settings.getHubUrl()) }
    var plugins by remember { mutableStateOf<List<RescuePluginListing>>(emptyList()) }
    var selected by remember { mutableStateOf<RescuePluginListing?>(null) }
    var installed by remember { mutableStateOf<InstalledRescuePlugin?>(null) }
    var comments by remember { mutableStateOf<List<RescuePluginComment>>(emptyList()) }
    var commentText by remember { mutableStateOf("") }
    var rating by remember { mutableFloatStateOf(5f) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun loadSelection(plugin: RescuePluginListing) {
        selected = plugin
        scope.launch {
            runCatching {
                installed = manager.installedPlugin(plugin.id)
                comments = manager.marketComments(plugin.id, plugin.version)
            }.onFailure { message = it.message ?: "Unable to load plugin details" }
        }
    }

    fun refreshCatalog() {
        loading = true
        scope.launch {
            runCatching {
                manager.ensureBundledFirstInstall()
                manager.marketPlugins()
            }.onSuccess { result ->
                plugins = result
                loading = false
                val preferred =
                    result.firstOrNull { it.id == (selected?.id ?: initialPluginId) }
                        ?: selected
                preferred?.let(::loadSelection)
            }.onFailure {
                loading = false
                message = it.message ?: "Unable to load plugin market"
            }
        }
    }

    LaunchedEffect(Unit) { refreshCatalog() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rescue_plugin_market_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = ::refreshCatalog, enabled = !loading) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.rescue_plugin_hub_url),
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = hubUrl,
                        onValueChange = { hubUrl = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedButton(
                        onClick = {
                            runCatching { manager.settings.setHubUrl(hubUrl) }
                                .onSuccess {
                                    hubUrl = it
                                    message = context.getString(R.string.rescue_plugin_saved)
                                    refreshCatalog()
                                }
                                .onFailure { message = it.message }
                        },
                    ) {
                        Text(stringResource(R.string.rescue_plugin_save))
                    }
                }
                message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (loading) {
                item { CircularProgressIndicator() }
            }

            items(plugins, key = { it.id }) { plugin ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { loadSelection(plugin) },
                    shape = MaterialTheme.shapes.small,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(plugin.name, style = MaterialTheme.typography.titleMedium)
                        Text("${plugin.id} · ${plugin.version}", style = MaterialTheme.typography.labelSmall)
                        if (plugin.description.isNotBlank()) {
                            Text(plugin.description, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            selected?.let { plugin ->
                item {
                    HorizontalDivider()
                    Text(plugin.name, style = MaterialTheme.typography.titleLarge)
                    Text(plugin.description, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        installed?.let { "Installed ${it.activeVersion}" }
                            ?: stringResource(R.string.rescue_plugin_not_installed),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                runCatching {
                                    if (installed == null) manager.install(plugin.id, plugin.version)
                                    else manager.update(plugin.id)
                                }.onSuccess {
                                    installed = it
                                    message = context.getString(R.string.rescue_plugin_install_complete)
                                }.onFailure { message = it.message }
                                busy = false
                            }
                        },
                    ) {
                        Icon(
                            imageVector = if (installed == null) Icons.Default.Download else Icons.Default.Refresh,
                            contentDescription = null,
                        )
                        Text(
                            if (installed == null) stringResource(R.string.rescue_plugin_install)
                            else stringResource(R.string.rescue_plugin_update),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }

                item {
                    Text(stringResource(R.string.rescue_plugin_comments), style = MaterialTheme.typography.titleMedium)
                    if (comments.isEmpty()) {
                        Text(stringResource(R.string.rescue_plugin_no_comments))
                    }
                }
                items(comments, key = { it.id.ifBlank { "${it.authorName}:${it.createdAt}:${it.content}" } }) { comment ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "${comment.authorName} · ${comment.authorType}" +
                                (comment.rating?.let { " · $it/5" } ?: ""),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(comment.content, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                item {
                    Text(stringResource(R.string.rescue_plugin_add_comment), style = MaterialTheme.typography.titleMedium)
                    Text("${rating.toInt()}/5", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = rating,
                        onValueChange = { rating = it },
                        valueRange = 1f..5f,
                        steps = 3,
                    )
                    OutlinedTextField(
                        value = commentText,
                        onValueChange = { commentText = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                    Button(
                        enabled = commentText.isNotBlank() && !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                runCatching {
                                    manager.publishUserComment(
                                        plugin.id,
                                        plugin.version,
                                        rating.toInt(),
                                        commentText,
                                    )
                                }.onSuccess { published ->
                                    comments = listOf(published) + comments
                                    commentText = ""
                                    message = context.getString(R.string.rescue_plugin_comment_published)
                                }.onFailure { message = it.message }
                                busy = false
                            }
                        },
                    ) {
                        Text(stringResource(R.string.rescue_plugin_publish))
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
