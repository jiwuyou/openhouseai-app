package com.ai.assistance.operit.ui.features.toolbox.screens.hostterminal

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.core.tools.system.Terminal
import com.ai.assistance.operit.host.terminal.HostTerminalTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class HostedTerminalRecord(
        val command: String,
        val target: HostTerminalTarget,
        val output: String,
        val error: String?,
        val timestamp: Long = System.currentTimeMillis()
) {
        val success: Boolean
                get() = error == null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostedTerminalScreen(forceShowSetup: Boolean = false) {
        val context = LocalContext.current
        val focusManager = LocalFocusManager.current
        val scope = rememberCoroutineScope()
        val commandHistory = remember { mutableStateListOf<HostedTerminalRecord>() }
        val sessionIds = remember { mutableStateMapOf<HostTerminalTarget, String>() }
        var selectedTarget by remember { mutableStateOf(HostTerminalTarget.DEFAULT) }
        var commandInput by remember { mutableStateOf("") }
        var sessionId by remember { mutableStateOf<String?>(null) }
        var isPreparing by remember { mutableStateOf(false) }
        var isExecuting by remember { mutableStateOf(false) }
        var setupError by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(forceShowSetup, selectedTarget) {
                if (!forceShowSetup) {
                        isPreparing = true
                        setupError = null
                        val result = prepareHostedTerminalSession(context, selectedTarget)
                        result.getOrNull()?.let { sessionIds[selectedTarget] = it }
                        sessionId = result.getOrNull()
                        setupError = result.exceptionOrNull()?.message
                        isPreparing = false
                }
        }

        fun submit(command: String) {
                val trimmedCommand = command.trim()
                if (trimmedCommand.isBlank() || isExecuting) return

                val blockedReason = terminalCommandBlockReason(trimmedCommand)
                if (blockedReason != null) {
                        commandHistory.add(
                                0,
                                HostedTerminalRecord(
                                        command = trimmedCommand,
                                        target = selectedTarget,
                                        output = "",
                                        error = blockedReason
                                )
                        )
                        return
                }

                isExecuting = true
                focusManager.clearFocus()
                val commandTarget = selectedTarget
                scope.launch {
                        val result =
                                withContext(Dispatchers.IO) {
                                        runCatching {
                                                val activeSessionId =
                                                        sessionIds[commandTarget]
                                                                ?: prepareHostedTerminalSession(context, commandTarget)
                                                                        .getOrThrow()
                                                val commandResult =
                                                        Terminal.getInstance(context)
                                                        .executeCommandResult(activeSessionId, trimmedCommand)
                                                Triple(activeSessionId, commandResult, commandTarget)
                                        }
                                }

                        result.getOrNull()?.let { (activeSessionId, _, target) ->
                                sessionIds[target] = activeSessionId
                                if (selectedTarget == target) {
                                        sessionId = activeSessionId
                                }
                        }
                        commandHistory.add(
                                0,
                                HostedTerminalRecord(
                                        command = trimmedCommand,
                                        target = commandTarget,
                                        output = result.getOrNull()?.second?.output.orEmpty(),
                                        error =
                                                result.exceptionOrNull()?.message
                                                        ?: result.getOrNull()?.second?.let { commandResult ->
                                                                if (commandResult.isOk) {
                                                                        null
                                                                } else {
                                                                        commandResult.error.ifBlank {
                                                                                commandResult.rawOutputPreview
                                                                        }
                                                                }
                                                        }
                                )
                        )
                        val commandResult = result.getOrNull()?.second
                        if (commandResult?.isOk == true) {
                                commandInput = ""
                                setupError = null
                        } else {
                                setupError =
                                        result.exceptionOrNull()?.message
                                                ?: commandResult?.error?.ifBlank {
                                                        commandResult.rawOutputPreview
                                                }
                        }
                        isExecuting = false
                }
        }

        Column(modifier = Modifier.fillMaxSize()) {
                Surface(
                        modifier = Modifier.fillMaxWidth(),
                        tonalElevation = 2.dp,
                        color = MaterialTheme.colorScheme.surface
                ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                                imageVector = Icons.Default.Terminal,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(28.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                        text = "${selectedTarget.displayName} 终端",
                                                        style =
                                                                MaterialTheme.typography.titleLarge.copy(
                                                                        fontWeight = FontWeight.Bold
                                                                )
                                                )
                                                Text(
                                                        text = "通过 SmallPhoneAI 宿主 adapter 执行 ${selectedTarget.displayName} 短命令",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                        }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                TerminalTargetSelector(
                                        selectedTarget = selectedTarget,
                                        onTargetSelected = {
                                                selectedTarget = it
                                                sessionId = sessionIds[it]
                                        }
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                TerminalStatusBanner(
                                        target = selectedTarget,
                                        isPreparing = isPreparing,
                                        sessionId = sessionId,
                                        setupError = setupError,
                                        forceShowSetup = forceShowSetup
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                ) {
                                        OutlinedTextField(
                                                value = commandInput,
                                                onValueChange = { commandInput = it },
                                                modifier = Modifier.weight(1f),
                                                placeholder = { Text("输入 ${selectedTarget.displayName} 命令") },
                                                leadingIcon = {
                                                        Icon(
                                                                imageVector = Icons.Default.Terminal,
                                                                contentDescription = null
                                                        )
                                                },
                                                trailingIcon = {
                                                        if (commandInput.isNotEmpty()) {
                                                                IconButton(onClick = { commandInput = "" }) {
                                                                        Icon(
                                                                                imageVector = Icons.Default.Clear,
                                                                                contentDescription = "Clear"
                                                                        )
                                                                }
                                                        }
                                                },
                                                keyboardOptions =
                                                        KeyboardOptions(imeAction = ImeAction.Send),
                                                keyboardActions =
                                                        KeyboardActions(
                                                                onSend = { submit(commandInput) }
                                                        ),
                                                singleLine = true,
                                                shape = RoundedCornerShape(18.dp),
                                                colors =
                                                        OutlinedTextFieldDefaults.colors(
                                                                focusedBorderColor =
                                                                        MaterialTheme.colorScheme.primary
                                                        )
                                        )

                                        Spacer(modifier = Modifier.width(8.dp))

                                        FilledTonalButton(
                                                onClick = { submit(commandInput) },
                                                enabled =
                                                        commandInput.trim().isNotEmpty() &&
                                                                !isPreparing &&
                                                                !isExecuting,
                                                contentPadding = PaddingValues(horizontal = 14.dp),
                                                colors = ButtonDefaults.filledTonalButtonColors()
                                        ) {
                                                if (isExecuting) {
                                                        CircularProgressIndicator(
                                                                modifier = Modifier.size(20.dp),
                                                                strokeWidth = 2.dp
                                                        )
                                                } else {
                                                        Icon(
                                                                imageVector =
                                                                        Icons.AutoMirrored.Filled.Send,
                                                                contentDescription = "Run"
                                                        )
                                                }
                                        }
                                }
                        }
                }

                if (commandHistory.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.padding(24.dp)
                                ) {
                                        Icon(
                                                imageVector = Icons.Default.Info,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(48.dp)
                                        )
                                        Text(
                                                text = "终端入口已迁移到宿主 Ubuntu/Termux",
                                                style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                                text = "后台服务、daemon 和长驻进程请通过 SmallPhoneAI service-manager 管理。",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                }
                        }
                } else {
                        LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                                items(commandHistory, key = { "${it.timestamp}-${it.command}" }) { record ->
                                        HostedTerminalRecordCard(record = record)
                                }
                        }
                }
        }
}

@Composable
private fun TerminalTargetSelector(
        selectedTarget: HostTerminalTarget,
        onTargetSelected: (HostTerminalTarget) -> Unit
) {
        Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
        ) {
                HostTerminalTarget.values().forEach { target ->
                        val selected = selectedTarget == target
                        FilledTonalButton(
                                onClick = { onTargetSelected(target) },
                                modifier = Modifier.weight(1f),
                                colors =
                                        ButtonDefaults.filledTonalButtonColors(
                                                containerColor =
                                                        if (selected)
                                                                MaterialTheme.colorScheme.primaryContainer
                                                        else
                                                                MaterialTheme.colorScheme.surfaceVariant,
                                                contentColor =
                                                        if (selected)
                                                                MaterialTheme.colorScheme.onPrimaryContainer
                                                        else
                                                                MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                        ) {
                                Text(target.displayName)
                        }
                }
        }
}

@Composable
private fun TerminalStatusBanner(
        target: HostTerminalTarget,
        isPreparing: Boolean,
        sessionId: String?,
        setupError: String?,
        forceShowSetup: Boolean
) {
        val (text, containerColor, contentColor) =
                when {
                        forceShowSetup ->
                                Triple(
                                        "终端自动配置由宿主 ${target.displayName}/service-manager 提供，此页面只承载 UI 入口。",
                                        MaterialTheme.colorScheme.secondaryContainer,
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                )
                        isPreparing ->
                                Triple(
                                        "正在连接宿主 ${target.displayName} adapter...",
                                        MaterialTheme.colorScheme.primaryContainer,
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                )
                        setupError != null ->
                                Triple(
                                        "宿主终端暂不可用：$setupError",
                                        MaterialTheme.colorScheme.errorContainer,
                                        MaterialTheme.colorScheme.onErrorContainer
                                )
                        sessionId != null ->
                                Triple(
                                        "已连接宿主 ${target.displayName} session",
                                        MaterialTheme.colorScheme.primaryContainer,
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                )
                        else ->
                                Triple(
                                        "等待宿主 ${target.displayName} adapter 初始化",
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                }

        Surface(
                modifier = Modifier.fillMaxWidth(),
                color = containerColor,
                contentColor = contentColor,
                shape = RoundedCornerShape(12.dp)
        ) {
                Text(
                        text = text,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall
                )
        }
}

@Composable
private fun HostedTerminalRecordCard(record: HostedTerminalRecord) {
        val dateFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
        val formattedDate = remember(record.timestamp) { dateFormatter.format(Date(record.timestamp)) }
        val statusColor = if (record.success) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error

        Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                shape = RoundedCornerShape(12.dp)
        ) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                        text = "[${record.target.wireName}] $ ${record.command}",
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style =
                                                MaterialTheme.typography.bodyMedium.copy(
                                                        fontFamily = FontFamily.Monospace,
                                                        fontWeight = FontWeight.Bold
                                                )
                                )
                                Text(
                                        text = formattedDate,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        val output = record.error ?: record.output.ifBlank { "(no output)" }
                        Box(
                                modifier =
                                        Modifier.fillMaxWidth()
                                                .background(
                                                        color =
                                                                if (record.success)
                                                                        MaterialTheme.colorScheme
                                                                                .surfaceVariant
                                                                                .copy(alpha = 0.5f)
                                                                else
                                                                        MaterialTheme.colorScheme
                                                                                .errorContainer
                                                                                .copy(alpha = 0.6f),
                                                        shape = RoundedCornerShape(8.dp)
                                                )
                                                .padding(10.dp)
                        ) {
                                Text(
                                        text = output,
                                        color =
                                                if (record.success)
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                else
                                                        MaterialTheme.colorScheme.onErrorContainer,
                                        style =
                                                MaterialTheme.typography.bodySmall.copy(
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 12.sp,
                                                        lineHeight = 16.sp
                                                )
                                )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                                text = if (record.success) "completed" else "blocked/failed",
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor
                        )
                }
        }
}

private suspend fun prepareHostedTerminalSession(
        context: Context,
        target: HostTerminalTarget
): Result<String> =
        runCatching {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                        error("Android 8.0+ required for hosted terminal adapter")
                }
                Terminal.getInstance(context).createSession("SmallPhoneAI ${target.displayName}", target)
        }

private fun terminalCommandBlockReason(command: String): String? {
        val normalized = command.trim().lowercase(Locale.ROOT)
        val backgroundPatterns =
                listOf(
                        Regex("""(^|[\s;])nohup([\s;]|$)"""),
                        Regex("""(^|[\s;])setsid([\s;]|$)"""),
                        Regex("""(^|[\s;])disown([\s;]|$)"""),
                        Regex("""(^|[\s;])daemon(ize)?([\s;]|$)"""),
                        Regex("""(^|[\s;])supervisord?([\s;]|$)"""),
                        Regex("""(^|[\s;])pm2\s+(start|restart|resurrect)\b"""),
                        Regex("""(^|[\s;])systemctl\s+(start|restart|enable|daemon-reload)\b"""),
                        Regex("""(^|[\s;])service\s+\S+\s+(start|restart|enable)\b"""),
                        Regex("""(^|[\s;])service-manager\b""")
                )

        if (normalized.endsWith("&") || backgroundPatterns.any { it.containsMatchIn(normalized) }) {
                return "长驻或后台命令必须交给 SmallPhoneAI service-manager，不能由 Operit UI 直接启动。"
        }

        return null
}
