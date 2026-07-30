package com.wuxianpi.assist

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wuxianpi.assist.protocol.ConversationMessage
import com.wuxianpi.assist.protocol.ConversationRole
import com.wuxianpi.assist.protocol.Permission
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistApp(viewModel: AssistViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("WuxianPi Assist", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (state.phase != AssistConnectionPhase.IDLE) {
                            Text(
                                text = state.statusText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    if (state.phase in ACTIVE_PHASES) {
                        IconButton(onClick = viewModel::endSession) {
                            Icon(Icons.Default.Close, contentDescription = "End session")
                        }
                    }
                },
            )
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            when {
                state.phase == AssistConnectionPhase.AUTHORIZED -> ConversationScreen(
                    state = state,
                    onSend = viewModel::sendMessage,
                    onEnd = viewModel::endSession,
                )

                state.phase == AssistConnectionPhase.VERIFYING -> VerificationScreen(
                    state = state,
                    onShare = {
                        val code = state.sasCode ?: return@VerificationScreen
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "WuxianPi verification code: $code")
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Send verification code"))
                    },
                    onEnd = viewModel::endSession,
                )

                state.phase in CONNECTION_PHASES -> ConnectionProgress(state, viewModel::endSession)

                else -> InviteScreen(
                    state = state,
                    onInviteChanged = viewModel::updateInviteText,
                    onPermissionSelected = viewModel::selectPermission,
                    onConnect = viewModel::connect,
                    onRetry = viewModel::retry,
                )
            }
        }
    }
}

@Composable
private fun InviteScreen(
    state: AssistUiState,
    onInviteChanged: (String) -> Unit,
    onPermissionSelected: (Permission) -> Unit,
    onConnect: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Join a Rescue AI session",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = state.inviteText,
            onValueChange = onInviteChanged,
            modifier = Modifier
                .fillMaxWidth()
                .height(152.dp),
            label = { Text("Invitation") },
            placeholder = { Text("wuxianpi-assist://join?...") },
            supportingText = { Text("Paste the invitation received from the device owner") },
        )

        Text("Requested access", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(
                selected = state.requestedPermission == Permission.VIEW,
                onClick = { onPermissionSelected(Permission.VIEW) },
                label = { Text("View") },
            )
            FilterChip(
                selected = state.requestedPermission == Permission.COLLABORATE,
                onClick = { onPermissionSelected(Permission.COLLABORATE) },
                label = { Text("Collaborate") },
            )
        }

        state.errorMessage?.let { ErrorText(it) }
        Button(
            onClick = if (state.invite != null && state.phase == AssistConnectionPhase.ERROR) {
                onRetry
            } else {
                onConnect
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.inviteText.isNotBlank(),
        ) {
            Icon(
                imageVector = if (state.phase == AssistConnectionPhase.ERROR) {
                    Icons.Default.Refresh
                } else {
                    Icons.Default.Lock
                },
                contentDescription = null,
            )
            Spacer(Modifier.width(8.dp))
            Text(if (state.phase == AssistConnectionPhase.ERROR) "Reconnect" else "Connect securely")
        }
    }
}

@Composable
private fun ConnectionProgress(state: AssistUiState, onEnd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(42.dp))
        Spacer(Modifier.height(20.dp))
        Text(
            text = state.statusText,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (state.reconnectAttempt > 0) {
            Text(
                text = "Attempt ${state.reconnectAttempt}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        state.errorMessage?.let {
            ErrorText(it, Modifier.padding(top = 12.dp))
        }
        OutlinedButton(onClick = onEnd, modifier = Modifier.padding(top = 22.dp)) {
            Text("Cancel")
        }
    }
}

@Composable
private fun VerificationScreen(
    state: AssistUiState,
    onShare: () -> Unit,
    onEnd: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(38.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("Verification code", style = MaterialTheme.typography.titleLarge)
        Text(
            text = formatSas(state.sasCode.orEmpty()),
            modifier = Modifier.padding(vertical = 26.dp),
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 42.sp,
        )
        Text(
            text = "Send this code to the device owner by phone or WeChat. Session content stays locked until they enter it.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.hostFingerprint?.let {
            Text(
                text = "Host ${shortFingerprint(it)}",
                modifier = Modifier.padding(top = 18.dp),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onShare, modifier = Modifier.padding(top = 24.dp)) {
            Icon(Icons.Default.Share, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Share code")
        }
        OutlinedButton(onClick = onEnd, modifier = Modifier.padding(top = 10.dp)) {
            Text("End session")
        }
    }
}

@Composable
private fun ConversationScreen(
    state: AssistUiState,
    onSend: (String) -> Boolean,
    onEnd: () -> Unit,
) {
    val listState = rememberLazyListState()
    val timeline = remember(state.messages, state.activity) {
        buildTimeline(state.messages, state.activity)
    }
    var composer by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(timeline.size, state.pendingMessages.size) {
        if (timeline.isNotEmpty()) listState.animateScrollToItem(timeline.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ConnectionBanner(state)
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (timeline.isEmpty()) {
                item("empty") {
                    Text(
                        text = "Waiting for conversation history",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(timeline, key = { it.key }) { item ->
                when (item) {
                    is TimelineItem.Message -> MessageBubble(item.message)
                    is TimelineItem.Activity -> ActivityRow(item.item)
                }
            }
            if (state.pendingMessages.isNotEmpty()) {
                item("pending") {
                    Text(
                        text = "Sending ${state.pendingMessages.size} message(s)",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        if (state.grantedPermission == Permission.COLLABORATE) {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(12.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = composer,
                    onValueChange = { composer = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message the Rescue AI") },
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (onSend(composer)) composer = ""
                        },
                    ),
                )
                IconButton(
                    onClick = { if (onSend(composer)) composer = "" },
                    enabled = composer.isNotBlank() && state.canSend,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        } else {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("View-only access", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onEnd) { Text("End") }
                }
            }
        }
    }
}

@Composable
private fun ConnectionBanner(state: AssistUiState) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when (state.grantedPermission) {
                    Permission.VIEW -> "VIEW"
                    Permission.COLLABORATE -> "COLLABORATE"
                    null -> "CONNECTING"
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = state.turnDetail ?: state.turnStatus?.name?.replace('_', ' ') ?: state.statusText,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MessageBubble(message: ConversationMessage) {
    val isUser = message.role == ConversationRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.86f),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    message.isError -> MaterialTheme.colorScheme.errorContainer
                    isUser -> MaterialTheme.colorScheme.primaryContainer
                    message.role == ConversationRole.TOOL -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    text = message.role.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(message.content, style = MaterialTheme.typography.bodyMedium)
                Timestamp(message.timestampMs, Modifier.align(Alignment.End))
            }
        }
    }
}

@Composable
private fun ActivityRow(item: AssistActivityItem) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = if (item.isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(item.title, style = MaterialTheme.typography.labelLarge)
            item.detail?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Timestamp(item.timestampMs, Modifier.align(Alignment.End))
        }
    }
}

@Composable
private fun Timestamp(timestampMs: Long, modifier: Modifier = Modifier) {
    Text(
        text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestampMs)),
        modifier = modifier.padding(top = 5.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ErrorText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
}

private sealed interface TimelineItem {
    val key: String
    val timestampMs: Long

    data class Message(val message: ConversationMessage) : TimelineItem {
        override val key: String = "message-${message.id}"
        override val timestampMs: Long = message.timestampMs
    }

    data class Activity(val item: AssistActivityItem) : TimelineItem {
        override val key: String = "activity-${item.id}"
        override val timestampMs: Long = item.timestampMs
    }
}

private fun buildTimeline(
    messages: List<ConversationMessage>,
    activity: List<AssistActivityItem>,
): List<TimelineItem> = buildList {
    messages.mapTo(this) { TimelineItem.Message(it) }
    activity.mapTo(this) { TimelineItem.Activity(it) }
}.sortedWith(compareBy<TimelineItem> { it.timestampMs }.thenBy { it.key })

private fun formatSas(value: String): String = when (value.length) {
    6 -> value.chunked(3).joinToString(" ")
    8 -> value.chunked(4).joinToString(" ")
    else -> value
}

private fun shortFingerprint(value: String): String = when {
    value.length <= 16 -> value
    else -> "${value.take(8)}...${value.takeLast(8)}"
}

private val ACTIVE_PHASES = setOf(
    AssistConnectionPhase.CONNECTING,
    AssistConnectionPhase.WAITING_FOR_HOST,
    AssistConnectionPhase.VERIFYING,
    AssistConnectionPhase.WAITING_FOR_APPROVAL,
    AssistConnectionPhase.AUTHORIZED,
    AssistConnectionPhase.RECONNECTING,
)

private val CONNECTION_PHASES = setOf(
    AssistConnectionPhase.CONNECTING,
    AssistConnectionPhase.WAITING_FOR_HOST,
    AssistConnectionPhase.WAITING_FOR_APPROVAL,
    AssistConnectionPhase.RECONNECTING,
)
