package com.ai.assistance.operit.ui.features.token.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R

@Composable
fun DeepSeekRechargeChooser(
    onAlipay: () -> Unit,
    onWechat: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.deepseek_recharge_choose_method)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.deepseek_recharge_same_page_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = onAlipay,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.deepseek_alipay_recharge))
                }
                Text(
                    text = stringResource(R.string.deepseek_alipay_recharge_description),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                OutlinedButton(
                    onClick = onWechat,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.deepseek_wechat_recharge))
                }
                Text(
                    text = stringResource(R.string.deepseek_wechat_recharge_description),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        },
        confirmButton = {},
        dismissButton = {},
    )
}
