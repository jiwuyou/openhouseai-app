package com.ai.assistance.operit.ui.features.chat.webview.computer

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.host.OperitHostProvider
import com.ai.assistance.operit.host.terminal.HostTerminalTarget
import com.ai.assistance.operit.host.executeServiceManagerRecoveryCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun ComputerScreen() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {},
                        onDoubleTap = {},
                        onLongPress = {},
                        onPress = {}
                    )
                }
    ) {
        HostTerminalPanel(modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun HostTerminalPanel(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val host = remember { OperitHostProvider.currentOrNull() }
    var command by remember { mutableStateOf("pwd") }
    var output by remember {
        mutableStateOf(
            if (host == null) {
                "SmallPhoneAI host terminal adapter is not installed."
            } else {
                "Ready. Commands are executed by the SmallPhoneAI Termux host adapter."
            }
        )
    }
    var running by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Termux",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = !running && host != null,
            singleLine = false,
            minLines = 2,
            label = { Text("Command") },
            placeholder = { Text("例如: ls -la") }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                enabled = !running && host != null && command.isNotBlank(),
                onClick = {
                    val hostContract = host ?: return@Button
                    val commandText = command.trim()
                    running = true
                    output = "$ $commandText\n"
                    scope.launch {
                        val result =
                            withContext(Dispatchers.IO) {
                                hostContract.executeServiceManagerRecoveryCommand(
                                    command = commandText,
                                    reason = "computer-screen"
                                ) ?: OperitHostProvider.operationsOrUnsupported().executeCommand(
                                    command = commandText,
                                    target = HostTerminalTarget.DEFAULT,
                                    timeoutMs = 60_000L,
                                )
                            }
                        output =
                            buildString {
                                append("$ ")
                                append(result.command)
                                append('\n')
                                if (result.stdout.isNotBlank()) {
                                    append(result.stdout.trimEnd())
                                    append('\n')
                                }
                                if (result.stderr.isNotBlank()) {
                                    append(result.stderr.trimEnd())
                                    append('\n')
                                }
                                if (result.error.isNotBlank()) {
                                    append("error: ")
                                    append(result.error)
                                    append('\n')
                                }
                                append("exitCode=")
                                append(result.exitCode)
                                append(", timedOut=")
                                append(result.timedOut)
                                append(", durationMs=")
                                append(result.durationMs)
                            }
                        running = false
                    }
                }
            ) {
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp).size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                Text("Run")
            }

            Text(
                text = if (host == null) "Host adapter unavailable" else "Short commands only",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Card(
            modifier = Modifier.fillMaxSize(),
            colors =
                CardDefaults.cardColors(
                    containerColor = Color(0xFF111111),
                    contentColor = Color(0xFFEAEAEA)
                )
        ) {
            SelectionContainer {
                Text(
                    text = output,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(12.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
