package com.wuxianpi.ai

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wuxianpi.pi.PiConnectionState
import org.json.JSONArray

@Composable
fun WuxianPiFeature(config: AiFeatureConfig, modifier: Modifier = Modifier) {
    val application = LocalContext.current.applicationContext as Application
    val factory = remember(config) { WuxianPiViewModel.factory(application, config) }
    val model: WuxianPiViewModel = viewModel(
        key = "wuxianpi-ai-${config.runtimeMode}",
        factory = factory,
    )
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = Color(0xFF4F5D2F),
            background = Color(0xFFF6F4EF),
            surface = Color(0xFFFCFBF8),
        ),
    ) {
        Surface(modifier.fillMaxSize()) {
            WuxianPiApp(model, config.runtimeMode)
        }
    }
}

@Composable
private fun WuxianPiApp(model: WuxianPiViewModel, runtimeMode: RuntimeMode) {
    val credentials by model.credentials.collectAsState()
    when {
        credentials != null -> ChatScreen(model, allowDisconnect = runtimeMode == RuntimeMode.EXTERNAL_TERMUX)
        runtimeMode == RuntimeMode.EXTERNAL_TERMUX -> PairingScreen(model)
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Bundled Pi runtime credentials are unavailable")
        }
    }
}

@Composable
private fun PairingScreen(model: WuxianPiViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var server by remember { mutableStateOf<PairingInstallerServer?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val installer = PairingInstallerServer(context) { credentials ->
            model.completePairing(credentials)
        }
        runCatching { installer.start() }
            .onSuccess { server = installer }
            .onFailure { error = it.message }
        onDispose { installer.close() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF6F4EF))
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("WuxianPi", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Connect the native interface to Pi running in your official Termux installation.")
        Spacer(Modifier.height(28.dp))
        Text("1. Open Termux\n2. Paste this one-time command\n3. Return here after installation")
        Spacer(Modifier.height(16.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF242620))) {
            SelectionContainer {
                Text(
                    server?.command ?: "Starting local installer…",
                    color = Color(0xFFE7E9DF),
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            enabled = server != null,
            onClick = { server?.command?.let { clipboard.setText(AnnotatedString(it)) } },
        ) { Text("Copy command") }
        error?.let {
            Spacer(Modifier.height(16.dp))
            ErrorBanner(it) { error = null }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "The installer is bound to 127.0.0.1, uses a single-use token, and closes when this screen closes.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF65675F),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(model: WuxianPiViewModel, allowDisconnect: Boolean) {
    val conversation by model.conversation.collectAsState()
    val connection by model.connection.collectAsState()
    val status by model.statusMessage.collectAsState()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    LaunchedEffect(conversation.messages.size, conversation.messages.lastOrNull()?.text) {
        if (conversation.messages.isNotEmpty()) listState.animateScrollToItem(conversation.messages.lastIndex)
    }

    conversation.extensionRequest?.let { request ->
        ExtensionDialog(
            method = request.method,
            title = request.payload.optString("title", "Pi needs your input"),
            message = request.payload.optString("message"),
            options = request.payload.optJSONArray("options"),
            onResponse = model::respondToExtension,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("WuxianPi", fontWeight = FontWeight.SemiBold)
                        ConnectionLabel(connection)
                    }
                },
                actions = {
                    if (allowDisconnect) {
                        TextButton(onClick = model::forgetRuntime) { Text("Disconnect") }
                    }
                },
            )
        },
        bottomBar = {
            Column(
                Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Ask Pi to do something…") },
                    minLines = 1,
                    maxLines = 5,
                    trailingIcon = {
                        if (conversation.isAgentRunning) {
                            TextButton(onClick = model::abort) { Text("Stop") }
                        } else {
                            TextButton(
                                enabled = input.isNotBlank() && connection is PiConnectionState.Connected,
                                onClick = {
                                    val value = input
                                    input = ""
                                    model.send(value)
                                },
                            ) { Text("Send") }
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            status?.let {
                ErrorBanner(
                    text = it,
                    action = when (connection) {
                        is PiConnectionState.LeaseConflict -> "Take over"
                        else -> "Retry"
                    },
                    onAction = when (connection) {
                        is PiConnectionState.LeaseConflict -> model::takeOver
                        else -> model::retryConnection
                    },
                )
            }
            if (conversation.messages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("What should we build?", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(8.dp))
                        Text("Pi can use Termux and Android tools while you watch each step.")
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(conversation.messages, key = { it.id }) { MessageCard(it) }
                    if (conversation.isAgentRunning) {
                        item("running") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text("Pi is working", Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionLabel(state: PiConnectionState) {
    val (label, color) = when (state) {
        is PiConnectionState.Connected -> "Connected" to Color(0xFF47722E)
        is PiConnectionState.Connecting, is PiConnectionState.AcquiringLease -> "Connecting" to Color(0xFF8A6D1F)
        is PiConnectionState.Recovering -> "Restoring session" to Color(0xFF8A6D1F)
        is PiConnectionState.Reconnecting -> "Reconnecting" to Color(0xFF8A6D1F)
        is PiConnectionState.LeaseConflict -> "Open elsewhere" to Color(0xFFA33A2B)
        is PiConnectionState.Failed -> "Connection error" to Color(0xFFA33A2B)
        PiConnectionState.Disconnected -> "Disconnected" to Color.Gray
    }
    Text(label, style = MaterialTheme.typography.labelSmall, color = color)
}

@Composable
private fun MessageCard(message: ChatMessageState) {
    val isUser = message.role == MessageRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(if (isUser) 0.86f else 1f),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    message.isError -> Color(0xFFFFE9E5)
                    isUser -> Color(0xFFE4E9D6)
                    else -> MaterialTheme.colorScheme.surface
                },
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                if (message.thinking.isNotBlank()) ThinkingBlock(message.thinking)
                if (message.text.isNotBlank()) SelectionContainer { Text(message.text) }
                message.tools.forEach { ToolCard(it) }
            }
        }
    }
}

@Composable
private fun ThinkingBlock(text: String) {
    var expanded by remember(text) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickable { expanded = !expanded },
    ) {
        Text(
            if (expanded) "Thinking ▾" else "Thinking ▸",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF72756C),
        )
        if (expanded) Text(text, style = MaterialTheme.typography.bodySmall, color = Color(0xFF5F625A))
    }
}

@Composable
private fun ToolCard(tool: ToolCardState) {
    var expanded by remember(tool.id) { mutableStateOf(tool.status == ToolStatus.RUNNING) }
    val color = when (tool.status) {
        ToolStatus.RUNNING -> Color(0xFF8A6D1F)
        ToolStatus.SUCCEEDED -> Color(0xFF47722E)
        ToolStatus.FAILED -> Color(0xFFA33A2B)
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0EFEA)),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tool.name, fontWeight = FontWeight.Medium)
                Text(tool.status.name.lowercase(), color = color, style = MaterialTheme.typography.labelMedium)
            }
            if (expanded) {
                if (tool.arguments.isNotBlank()) {
                    Text(tool.arguments, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                if (tool.output.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer {
                        Text(tool.output, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(text: String, action: String = "Dismiss", onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFFFFE9E5)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), color = Color(0xFF78291F))
        TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun ExtensionDialog(
    method: String,
    title: String,
    message: String,
    options: JSONArray?,
    onResponse: (Any?) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onResponse(null) },
        title = { Text(title) },
        text = {
            Column {
                if (message.isNotBlank()) Text(message)
                when (method) {
                    "input", "editor" -> OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth())
                    "select" -> options?.let { values ->
                        for (index in 0 until values.length()) {
                            val raw = values.opt(index)
                            val label = (raw as? org.json.JSONObject)?.optString("label") ?: raw.toString()
                            val value = (raw as? org.json.JSONObject)?.opt("value") ?: raw
                            OutlinedButton(onClick = { onResponse(value) }, Modifier.fillMaxWidth()) {
                                Text(label)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (method != "select") {
                Button(onClick = { onResponse(if (method == "confirm") true else input) }) { Text("Continue") }
            }
        },
        dismissButton = { TextButton(onClick = { onResponse(null) }) { Text("Cancel") } },
    )
}
