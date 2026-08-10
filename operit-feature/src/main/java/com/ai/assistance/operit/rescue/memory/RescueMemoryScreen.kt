package com.ai.assistance.operit.rescue.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.rescue.plugins.RescuePluginManager
import kotlinx.coroutines.launch

@Composable
fun RescueMemoryScreen() {
    val context = LocalContext.current
    val manager = remember { RescuePluginManager.get(context) }
    val snapshot by manager.memorySnapshot.collectAsState()
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(snapshot.markdown) }
    var loadedRevision by remember { mutableStateOf(snapshot.revision) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(snapshot.revision) {
        if (loadedRevision == snapshot.revision || draft == snapshot.markdown) {
            draft = snapshot.markdown
            loadedRevision = snapshot.revision
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AI 记忆", style = MaterialTheme.typography.titleLarge)
        Text(
            "revision ${snapshot.revision} · ${snapshot.updatedAt}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 640.dp),
            enabled = !busy,
            label = { Text("Markdown") },
        )
        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy && draft != snapshot.markdown,
                onClick = {
                    scope.launch {
                        busy = true
                        runCatching { manager.replaceMemory(loadedRevision, draft) }
                            .onSuccess {
                                draft = it.markdown
                                loadedRevision = it.revision
                                message = "记忆已保存"
                            }
                            .onFailure { message = it.message ?: "保存失败" }
                        busy = false
                    }
                },
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Text("保存", modifier = Modifier.padding(start = 6.dp))
            }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        runCatching { manager.undoMemory() }
                            .onSuccess {
                                draft = it.markdown
                                loadedRevision = it.revision
                                message = "已恢复上一版本"
                            }
                            .onFailure { message = it.message ?: "没有可恢复版本" }
                        busy = false
                    }
                },
            ) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null)
                Text("撤销", modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}
