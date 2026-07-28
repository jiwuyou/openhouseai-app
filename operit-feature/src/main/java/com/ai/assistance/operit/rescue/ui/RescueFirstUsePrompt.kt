package com.ai.assistance.operit.rescue.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R

const val RESCUE_FIRST_USE_MESSAGE =
    "这是我第一次使用，请检查当前环境，并引导我完成 WuxianPi 初始化。"

internal fun shouldShowRescueFirstUsePrompt(
    isRescueContext: Boolean,
    hasCurrentConversation: Boolean,
    persistedMessageCount: Int?,
    visibleMessageCount: Int,
    isHistoryLoading: Boolean,
): Boolean =
    isRescueContext &&
        hasCurrentConversation &&
        persistedMessageCount == 0 &&
        visibleMessageCount == 0 &&
        !isHistoryLoading

@Composable
fun RescueFirstUsePrompt(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
    ) {
        Icon(
            imageVector = Icons.Default.Build,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.rescue_first_use_action),
            modifier = Modifier.weight(1f),
        )
    }
}
