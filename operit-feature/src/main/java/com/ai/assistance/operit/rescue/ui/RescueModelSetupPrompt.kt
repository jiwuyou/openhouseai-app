package com.ai.assistance.operit.rescue.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

const val RESCUE_DEEPSEEK_PAGE_ID = "openhouse.api-keys"

@Composable
fun RescueModelSetupPrompt(
    saving: Boolean,
    error: String?,
    onSaveDeepSeekKey: (String) -> Unit,
    onOpenDeepSeekPage: () -> Unit,
    onConfigureOtherModel: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var inputExpanded by rememberSaveable { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf("") }
    var revealKey by remember { mutableStateOf(false) }
    var showDeepSeekNotice by rememberSaveable { mutableStateOf(false) }

    if (showDeepSeekNotice) {
        AlertDialog(
            onDismissRequest = { showDeepSeekNotice = false },
            title = { Text("前往 DeepSeek 获取密钥") },
            text = {
                Text(
                    "DeepSeek 是第三方服务，OpenHouse/WuxianPi 与 DeepSeek 没有合作关系。\n\n" +
                        "你需要自行注册 DeepSeek 账号，并在 DeepSeek 官方网站创建 API Key。" +
                        "通常充值约 5 元即可进行初步测试，实际价格、最低充值金额和消耗以 " +
                        "DeepSeek 官方页面为准。\n\n" +
                        "创建密钥后，请复制回来粘贴到维修助手的输入框。" +
                        "OpenHouse 不会自动读取网页中的密钥。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeepSeekNotice = false
                        inputExpanded = true
                        onOpenDeepSeekPage()
                    }
                ) {
                    Text("继续前往 DeepSeek")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeepSeekNotice = false }) {
                    Text("取消")
                }
            },
        )
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "维修助手还没有配置可用模型",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "填写 DeepSeek 密钥即可立即使用，也可以获取密钥或配置其他模型。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (inputExpanded) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving,
                    singleLine = true,
                    label = { Text("DeepSeek API 密钥") },
                    placeholder = { Text("请输入或粘贴 API Key") },
                    leadingIcon = {
                        Icon(Icons.Default.Key, contentDescription = null)
                    },
                    trailingIcon = {
                        IconButton(onClick = { revealKey = !revealKey }) {
                            Icon(
                                if (revealKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (revealKey) "隐藏密钥" else "显示密钥",
                            )
                        }
                    },
                    visualTransformation =
                        if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (!saving && apiKey.isNotBlank()) onSaveDeepSeekKey(apiKey)
                        }
                    ),
                )
                error?.takeIf { it.isNotBlank() }?.let { message ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { onSaveDeepSeekKey(apiKey) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving && apiKey.isNotBlank(),
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp).width(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (saving) "正在保存" else "填写并立即使用")
                }
            } else {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { inputExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Key, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("填写 DeepSeek 密钥")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { showDeepSeekNotice = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("获取 DeepSeek 密钥")
                }
                OutlinedButton(
                    onClick = onConfigureOtherModel,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("配置其他模型")
                }
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("稍后处理")
            }
        }
    }
}
