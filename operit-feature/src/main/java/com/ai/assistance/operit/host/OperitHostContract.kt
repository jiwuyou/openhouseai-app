package com.ai.assistance.operit.host

import android.content.Context

interface OperitHostContract {
    val applicationContext: Context

    suspend fun executeTermuxCommand(command: String, timeoutMs: Long = 15_000L): OperitHostCommandResult

    suspend fun executeUbuntuCommand(command: String, timeoutMs: Long = 15_000L): OperitHostCommandResult

    suspend fun queryServiceManagerHealth(): OperitHostServiceManagerResult

    suspend fun queryServiceManagerStatus(serviceId: String): OperitHostServiceManagerResult
}
