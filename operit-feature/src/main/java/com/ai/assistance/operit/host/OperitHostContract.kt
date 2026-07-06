package com.ai.assistance.operit.host

import android.content.Context

interface OperitHostContract {
    val applicationContext: Context

    suspend fun executeTermuxCommand(command: String, timeoutMs: Long = 15_000L): OperitHostCommandResult

    suspend fun executeUbuntuCommand(command: String, timeoutMs: Long = 15_000L): OperitHostCommandResult

    suspend fun queryServiceManagerHealth(): OperitHostServiceManagerResult

    suspend fun queryServiceManagerStatus(serviceId: String): OperitHostServiceManagerResult
}

interface OperitHostServiceManagerRecovery {
    suspend fun recoverServiceManagerControlPlane(reason: String): OperitHostServiceManagerResult
}

enum class OperitHostServiceManagerRecoveryAction(val wireName: String) {
    REPAIR("repair"),
    RECOVER("recover");

    companion object {
        fun fromCommand(command: String): OperitHostServiceManagerRecoveryAction? {
            val action =
                SERVICE_MANAGER_RECOVERY_COMMAND
                    .matchEntire(command.trim())
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: return null
            return entries.firstOrNull { it.wireName.equals(action, ignoreCase = true) }
        }
    }
}

suspend fun OperitHostContract.recoverServiceManagerControlPlane(
    reason: String
): OperitHostServiceManagerResult {
    val recoveryHost = this as? OperitHostServiceManagerRecovery
    if (recoveryHost != null) {
        return recoveryHost.recoverServiceManagerControlPlane(reason)
    }
    return unsupportedServiceManagerRecovery(reason)
}

suspend fun OperitHostContract.executeServiceManagerRecoveryCommand(
    command: String,
    reason: String
): OperitHostCommandResult? {
    val cleanCommand = command.trim()
    val action = OperitHostServiceManagerRecoveryAction.fromCommand(cleanCommand) ?: return null
    val startedAtMs = System.currentTimeMillis()
    val result =
        runCatching {
                recoverServiceManagerControlPlane("$reason:${action.wireName}")
            }
            .getOrElse { throwable ->
                serviceManagerRecoveryException(throwable, startedAtMs)
            }
    return result.toRecoveryCommandResult(cleanCommand, action, startedAtMs)
}

private val SERVICE_MANAGER_RECOVERY_COMMAND =
    Regex("""^/?service-manager\s+(repair|recover)\s*$""", RegexOption.IGNORE_CASE)

private fun unsupportedServiceManagerRecovery(reason: String): OperitHostServiceManagerResult =
    OperitHostServiceManagerResult(
        success = false,
        code = 501,
        url = "",
        body = "",
        message = "service-manager recovery is not implemented by this host.",
        serviceId = "service-manager",
        state = "",
        provider = "operit-host",
        pid = -1,
        serviceUrl = "",
        error = "Host does not implement OperitHostServiceManagerRecovery. reason=$reason",
        durationMs = 0L
    )

private fun serviceManagerRecoveryException(
    throwable: Throwable,
    startedAtMs: Long
): OperitHostServiceManagerResult =
    OperitHostServiceManagerResult(
        success = false,
        code = 0,
        url = "",
        body = "",
        message = "service-manager recovery request failed.",
        serviceId = "service-manager",
        state = "",
        provider = "operit-host",
        pid = -1,
        serviceUrl = "",
        error = throwable.message ?: throwable::class.java.simpleName,
        durationMs = System.currentTimeMillis() - startedAtMs
    )

private fun OperitHostServiceManagerResult.toRecoveryCommandResult(
    command: String,
    action: OperitHostServiceManagerRecoveryAction,
    startedAtMs: Long
): OperitHostCommandResult {
    val summary =
        buildString {
            append("service-manager ")
            append(action.wireName)
            append(" routed through SmallPhoneAI typed host API.")
            if (message.isNotBlank()) {
                append('\n')
                append(message)
            }
            if (state.isNotBlank() || provider.isNotBlank() || pid > 0) {
                append('\n')
                append("state=")
                append(state.ifBlank { "unknown" })
                if (provider.isNotBlank()) {
                    append(", provider=")
                    append(provider)
                }
                if (pid > 0) {
                    append(", pid=")
                    append(pid)
                }
            }
            if (serviceUrl.isNotBlank()) {
                append('\n')
                append("serviceUrl=")
                append(serviceUrl)
            }
            if (body.isNotBlank()) {
                append('\n')
                append(body.trim())
            }
        }
    val failure = error.ifBlank { message.ifBlank { "service-manager recovery failed." } }
    val elapsedMs = durationMs.takeIf { it > 0L } ?: (System.currentTimeMillis() - startedAtMs)
    return OperitHostCommandResult(
        command = command,
        exitCode = if (success) 0 else code.takeIf { it in 1..255 } ?: 1,
        stdout = summary,
        stderr = "",
        error = if (success) "" else failure,
        timedOut = false,
        durationMs = elapsedMs
    )
}
