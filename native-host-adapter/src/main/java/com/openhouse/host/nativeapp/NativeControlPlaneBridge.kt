package com.openhouse.host.nativeapp

import android.content.Context
import com.wuxianpi.openhouse.core.ControlPlaneBridge
import com.wuxianpi.openhouse.core.ControlPlaneCommandResult
import com.wuxianpi.openhouse.core.ControlPlaneOutputListener
import kotlinx.coroutines.runBlocking

internal class NativeControlPlaneBridge(context: Context) : ControlPlaneBridge {
    private val appContext = context.applicationContext ?: context
    private val transport = NativeTermuxRunCommandTransport(appContext)

    override fun start(listener: ControlPlaneOutputListener?): ControlPlaneCommandResult = runBlocking {
        try {
            val response = transport.execute(
                buildNativeControlPlaneStartRequest(),
                START_TIMEOUT_MS,
            )
            emit("stdout", response.stdout, listener)
            emit("stderr", response.stderr, listener)
            val exitCode = when {
                response.timedOut -> 124
                response.exitCode >= 0 -> response.exitCode
                response.errorCode != TERMUX_SUCCESS_ERROR_CODE -> 1
                else -> response.exitCode
            }
            val transportError = response.errorMessage.takeIf {
                response.errorCode != TERMUX_SUCCESS_ERROR_CODE && response.stderr.isBlank()
            }.orEmpty()
            if (transportError.isNotBlank()) emit("stderr", transportError, listener)
            ControlPlaneCommandResult(
                exitCode,
                response.stdout,
                listOf(response.stderr, transportError).filter(String::isNotBlank).joinToString("\n"),
            )
        } catch (error: Throwable) {
            val exitCode = (error as? NativeTermuxTransportException)?.exitCode ?: 1
            val message = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
            emit("stderr", message, listener)
            ControlPlaneCommandResult(exitCode, "", message)
        }
    }

    private fun emit(stream: String, output: String, listener: ControlPlaneOutputListener?) {
        output.lineSequence().filter(String::isNotEmpty).forEach { listener?.onOutput(stream, it) }
    }

    private companion object {
        const val START_TIMEOUT_MS = 45_000L
    }
}

internal fun buildNativeControlPlaneStartRequest(
    termuxPackage: String = EXTERNAL_TERMUX_PACKAGE,
): NativeTermuxCommandRequest {
    val prefix = "/data/data/$termuxPackage/files/usr"
    val home = "/data/data/$termuxPackage/files/home"
    return NativeTermuxCommandRequest(
        command = "$prefix/bin/openhouse-control-plane-start",
        executable = "$prefix/bin/openhouse-control-plane-start",
        arguments = emptyList(),
        workingDirectory = home,
    )
}
