package com.wuxianpi.assist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wuxianpi.assist.protocol.Permission
import kotlinx.coroutines.flow.StateFlow

class AssistViewModel(application: Application) : AndroidViewModel(application) {
    private val identityStore = AssistIdentityStore()
    private val controller = AssistSessionController(
        scope = viewModelScope,
        signerProvider = identityStore::getOrCreateSigner,
    )

    val state: StateFlow<AssistUiState> = controller.state

    fun updateInviteText(value: String) = controller.updateInviteText(value)

    fun acceptInvite(value: String, autoConnect: Boolean = false): Boolean =
        controller.acceptInvite(value, autoConnect)

    fun selectPermission(permission: Permission) = controller.selectPermission(permission)

    fun connect() = controller.connect()

    fun retry() = controller.retry()

    fun sendMessage(value: String): Boolean = controller.sendUserMessage(value)

    fun endSession() = controller.endSession()

    override fun onCleared() {
        controller.close()
        super.onCleared()
    }
}
