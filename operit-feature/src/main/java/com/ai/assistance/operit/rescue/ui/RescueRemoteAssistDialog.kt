package com.ai.assistance.operit.rescue.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.rescue.remote.RescueAssistHostPhase
import com.ai.assistance.operit.rescue.remote.RescueRemoteAssistController
import com.wuxianpi.assist.protocol.Permission

private const val ASSIST_PREFERENCES = "rescue_remote_assist"
private const val RELAY_URL_KEY = "relay_url"
private const val DEFAULT_RELAY_URL = "ws://127.0.0.1:20876"

@Composable
fun RescueRemoteAssistDialog(
    sharedChatId: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val controller = remember(context) { RescueRemoteAssistController.getInstance(context) }
    val state by controller.state.collectAsState()
    val preferences = remember(context) {
        context.getSharedPreferences(ASSIST_PREFERENCES, Context.MODE_PRIVATE)
    }
    var relayUrl by rememberSaveable {
        mutableStateOf(preferences.getString(RELAY_URL_KEY, DEFAULT_RELAY_URL) ?: DEFAULT_RELAY_URL)
    }
    var permissionName by rememberSaveable { mutableStateOf(Permission.COLLABORATE.name) }
    var sasCode by rememberSaveable { mutableStateOf("") }
    val permission = runCatching { Permission.valueOf(permissionName) }.getOrDefault(Permission.VIEW)
    val canStart = sharedChatId.isNotBlank() && relayUrl.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Share, contentDescription = null) },
        title = { Text("远程协助") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(statusText(state.phase))

                if (state.phase == RescueAssistHostPhase.IDLE || state.phase == RescueAssistHostPhase.ERROR) {
                    OutlinedTextField(
                        value = relayUrl,
                        onValueChange = { relayUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("中继 WebSocket 地址") },
                        singleLine = true,
                    )
                    Text("允许的协助权限")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = permission == Permission.VIEW,
                            onClick = { permissionName = Permission.VIEW.name },
                            label = { Text("只读") },
                        )
                        FilterChip(
                            selected = permission == Permission.COLLABORATE,
                            onClick = { permissionName = Permission.COLLABORATE.name },
                            label = { Text("协作发言") },
                        )
                    }
                    if (sharedChatId.isBlank()) {
                        Text("请先打开或创建一个救援对话。")
                    }
                }

                state.inviteUri?.let { invite ->
                    OutlinedTextField(
                        value = invite,
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("协助邀请") },
                        readOnly = true,
                        minLines = 3,
                        maxLines = 5,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(invite)) }) {
                            Text("复制邀请")
                        }
                        OutlinedButton(onClick = { shareText(context, "WuxianPi 远程协助邀请", invite) }) {
                            Text("发送邀请")
                        }
                    }
                }

                if (state.phase == RescueAssistHostPhase.CONNECTING ||
                    state.phase == RescueAssistHostPhase.WAITING_FOR_PEER
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 4.dp))
                }

                if (state.phase == RescueAssistHostPhase.WAITING_FOR_SAS) {
                    state.peerFingerprint?.let { Text("协助方身份：${shortFingerprint(it)}") }
                    Text("让协助方通过电话、微信等第三方发送验证码，再在这里填写。验证码不会经过中继服务器。")
                    OutlinedTextField(
                        value = sasCode,
                        onValueChange = { value -> sasCode = value.filter(Char::isDigit).take(8) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("验证码") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                    )
                }

                if (state.phase == RescueAssistHostPhase.AUTHORIZED) {
                    Text("端到端加密连接已建立。")
                    state.peerDisplayName?.let { Text("协助方：$it") }
                    state.grantedPermission?.let {
                        Text(if (it == Permission.COLLABORATE) "权限：协作发言" else "权限：只读")
                    }
                }

                state.error?.let { Text("错误：$it") }
            }
        },
        confirmButton = {
            when (state.phase) {
                RescueAssistHostPhase.IDLE,
                RescueAssistHostPhase.ERROR,
                -> TextButton(
                    enabled = canStart,
                    onClick = {
                        val normalizedRelay = relayUrl.trim()
                        preferences.edit().putString(RELAY_URL_KEY, normalizedRelay).apply()
                        controller.startSharing(sharedChatId, normalizedRelay, permission)
                    },
                ) {
                    Text(if (state.phase == RescueAssistHostPhase.ERROR) "重新创建" else "创建分享")
                }

                RescueAssistHostPhase.WAITING_FOR_SAS -> TextButton(
                    enabled = sasCode.length in setOf(6, 8),
                    onClick = { controller.verifySas(sasCode) },
                ) {
                    Text("验证")
                }

                else -> Unit
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.isSharing) {
                    TextButton(onClick = controller::stopSharing) { Text("停止共享") }
                }
                TextButton(onClick = onDismiss) { Text(if (state.isSharing) "隐藏" else "关闭") }
            }
        },
    )
}

private fun statusText(phase: RescueAssistHostPhase): String = when (phase) {
    RescueAssistHostPhase.IDLE -> "分享当前救援对话，协助服务器只能看到密文。"
    RescueAssistHostPhase.CONNECTING -> "正在连接中继服务器。"
    RescueAssistHostPhase.WAITING_FOR_PEER -> "等待协助方打开邀请。"
    RescueAssistHostPhase.WAITING_FOR_SAS -> "等待你核对协助方发来的验证码。"
    RescueAssistHostPhase.AUTHORIZED -> "协助会话正在进行。"
    RescueAssistHostPhase.ERROR -> "分享连接失败，可以修改地址后重新创建。"
}

private fun shareText(context: Context, title: String, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, title))
}

private fun shortFingerprint(value: String): String =
    if (value.length <= 18) value else "${value.take(9)}...${value.takeLast(9)}"
