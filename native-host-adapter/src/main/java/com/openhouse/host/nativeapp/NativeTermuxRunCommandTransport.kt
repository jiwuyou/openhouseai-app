package com.openhouse.host.nativeapp

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.ai.assistance.operit.host.OperitHostCommandResult
import com.ai.assistance.operit.host.terminal.tmux.TermuxSessionTransport
import com.ai.assistance.operit.host.terminal.tmux.TermuxTransportResult
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal const val EXTERNAL_TERMUX_PACKAGE = "com.termux"
internal const val TERMUX_SUCCESS_ERROR_CODE = -1

internal enum class ExternalTermuxCommandTarget {
    TERMUX,
    UBUNTU,
}

internal data class NativeTermuxCommandRequest(
    val command: String,
    val executable: String,
    val arguments: List<String>,
    val workingDirectory: String,
    val stdin: String? = null,
)

internal data class NativeTermuxCommandResponse(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val errorCode: Int,
    val errorMessage: String,
    val timedOut: Boolean = false,
)

internal fun interface NativeTermuxCommandTransport {
    suspend fun execute(
        request: NativeTermuxCommandRequest,
        timeoutMs: Long,
    ): NativeTermuxCommandResponse
}

internal class ExternalTermuxCommandExecutor(
    private val transport: NativeTermuxCommandTransport,
    private val termuxPackage: String = EXTERNAL_TERMUX_PACKAGE,
) {
    suspend fun execute(
        command: String,
        target: ExternalTermuxCommandTarget,
        timeoutMs: Long,
    ): OperitHostCommandResult {
        val startedAt = System.currentTimeMillis()
        val cleanCommand = command.trim()
        if (cleanCommand.isEmpty()) {
            return failure(command, 2, "command is empty", startedAt)
        }

        val request = buildNativeTermuxCommandRequest(cleanCommand, target, termuxPackage)
        return try {
            transport.execute(request, timeoutMs.coerceAtLeast(1L)).toHostResult(command, startedAt)
        } catch (error: CancellationException) {
            throw error
        } catch (error: NativeTermuxTransportException) {
            failure(command, error.exitCode, error.message.orEmpty(), startedAt)
        } catch (error: SecurityException) {
            failure(command, 126, error.message ?: "Termux RUN_COMMAND permission denied", startedAt)
        } catch (error: Throwable) {
            failure(command, 1, error.message ?: error.javaClass.simpleName, startedAt)
        }
    }

    private fun NativeTermuxCommandResponse.toHostResult(
        originalCommand: String,
        startedAt: Long,
    ): OperitHostCommandResult {
        val resolvedExitCode = when {
            timedOut -> 124
            exitCode >= 0 -> exitCode
            errorCode != TERMUX_SUCCESS_ERROR_CODE -> 1
            else -> exitCode
        }
        val resolvedError = when {
            timedOut -> errorMessage.ifBlank { "Termux command timed out" }
            errorCode != TERMUX_SUCCESS_ERROR_CODE ->
                errorMessage.ifBlank { "Termux RUN_COMMAND transport error $errorCode" }
            resolvedExitCode != 0 -> stderr.ifBlank { stdout }.ifBlank { "Command exited with code $resolvedExitCode" }
            else -> ""
        }
        return OperitHostCommandResult(
            command = originalCommand,
            exitCode = resolvedExitCode,
            stdout = stdout,
            stderr = stderr,
            error = resolvedError,
            timedOut = timedOut,
            durationMs = System.currentTimeMillis() - startedAt,
        )
    }

    private fun failure(
        command: String,
        exitCode: Int,
        message: String,
        startedAt: Long,
    ) = OperitHostCommandResult(
        command = command,
        exitCode = exitCode,
        stdout = "",
        stderr = "",
        error = message,
        timedOut = false,
        durationMs = System.currentTimeMillis() - startedAt,
    )
}

internal fun buildNativeTermuxCommandRequest(
    command: String,
    target: ExternalTermuxCommandTarget,
    termuxPackage: String = EXTERNAL_TERMUX_PACKAGE,
): NativeTermuxCommandRequest {
    val prefix = "/data/data/$termuxPackage/files/usr"
    val home = "/data/data/$termuxPackage/files/home"
    val environment = listOf(
        "HOME=$home",
        "PREFIX=$prefix",
        "PATH=$home/.pi/bin:$home/.local/bin:$prefix/bin:/system/bin:/system/xbin",
    )
    return if (target == ExternalTermuxCommandTarget.UBUNTU) {
        NativeTermuxCommandRequest(
            command = command,
            executable = "$prefix/bin/env",
            arguments = environment + listOf(
                "$prefix/bin/proot-distro", "login", "ubuntu", "--", "bash", "-lc", command,
            ),
            workingDirectory = home,
        )
    } else {
        NativeTermuxCommandRequest(
            command = command,
            executable = "$prefix/bin/env",
            arguments = environment + listOf("$prefix/bin/bash", "-lc", command),
            workingDirectory = home,
        )
    }
}

internal fun interface NativeTermuxRawCommandTransport {
    suspend fun execute(
        request: NativeTermuxCommandRequest,
        timeoutMs: Long,
    ): NativeTermuxCommandResponse?
}

internal class NativeTermuxManagedCommandTransport(
    private val rawTransport: NativeTermuxRawCommandTransport,
) : NativeTermuxCommandTransport {
    // The Android wait is bounded, but the RUN_COMMAND process remains owned by Termux.
    override suspend fun execute(
        request: NativeTermuxCommandRequest,
        timeoutMs: Long,
    ): NativeTermuxCommandResponse {
        val effectiveTimeout = timeoutMs.coerceAtLeast(1L)
        val response = rawTransport.execute(request, effectiveTimeout)
        if (response != null) return response

        return NativeTermuxCommandResponse(
            stdout = "",
            stderr = "",
            exitCode = 124,
            errorCode = 1,
            errorMessage = "Timed out waiting for Termux command result after ${effectiveTimeout}ms; remote execution continues",
            timedOut = true,
        )
    }
}

internal class NativeTermuxRunCommandTransport(
    context: Context,
    termuxPackage: String = EXTERNAL_TERMUX_PACKAGE,
    processNameProvider: (Context) -> String = NativeProcessNameResolver::currentProcessName,
) : NativeTermuxCommandTransport {
    private val delegate = NativeTermuxManagedCommandTransport(
        rawTransport = AndroidNativeTermuxRawCommandTransport(
            context = context,
            termuxPackage = termuxPackage,
            processNameProvider = processNameProvider,
        ),
    )

    override suspend fun execute(
        request: NativeTermuxCommandRequest,
        timeoutMs: Long,
    ): NativeTermuxCommandResponse = delegate.execute(request, timeoutMs)
}

private class AndroidNativeTermuxRawCommandTransport(
    context: Context,
    private val termuxPackage: String,
    private val processNameProvider: (Context) -> String,
) : NativeTermuxRawCommandTransport {
    private val appContext = context.applicationContext ?: context

    override suspend fun execute(
        request: NativeTermuxCommandRequest,
        timeoutMs: Long,
    ): NativeTermuxCommandResponse? {
        ensureAvailable()
        val effectiveTimeout = timeoutMs.coerceAtLeast(1L)
        val result = withTimeoutOrNull(effectiveTimeout) {
            suspendCancellableCoroutine<NativeTermuxCommandResult> { continuation ->
                val receiverClass = NativeTermuxReceiverSelector.receiverClassFor(
                    processNameProvider(appContext),
                    appContext.packageName,
                )
                val pendingRequest = NativeTermuxCommandResultCallbacks.createPendingIntent(
                    appContext,
                    receiverClass,
                ) { commandResult ->
                    if (continuation.isActive) continuation.resume(commandResult)
                }
                continuation.invokeOnCancellation { pendingRequest.cancel() }

                try {
                    appContext.startForegroundService(buildRunCommandIntent(request, pendingRequest.pendingIntent))
                } catch (error: Throwable) {
                    pendingRequest.cancel()
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }
        return result?.let {
            NativeTermuxCommandResponse(
                stdout = it.stdout,
                stderr = it.stderr,
                exitCode = it.exitCode,
                errorCode = it.errorCode,
                errorMessage = it.errorMessage,
            )
        }
    }

    private fun ensureAvailable() {
        val host = NativeExternalHostInspector.inspect(appContext)
        if (!canUseTermuxRunCommand(host)) {
            val missingPackage = host.state == NativeExternalHostState.ABSENT
            val exitCode = if (missingPackage) 127 else 126
            val message = if (missingPackage) {
                "Termux package $termuxPackage is not installed"
            } else {
                "Installed Termux does not expose $TERMUX_RUN_COMMAND_ACTION"
            }
            throw NativeTermuxTransportException(exitCode, message)
        }
        val permission = "$termuxPackage.permission.RUN_COMMAND"
        if (appContext.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            throw NativeTermuxTransportException(126, "Missing $permission")
        }
    }

    private fun buildRunCommandIntent(
        request: NativeTermuxCommandRequest,
        pendingIntent: android.app.PendingIntent,
    ): Intent = Intent("$termuxPackage.RUN_COMMAND").apply {
        component = ComponentName(termuxPackage, "$termuxPackage.app.RunCommandService")
        putExtra("$termuxPackage.RUN_COMMAND_PATH", request.executable)
        putExtra("$termuxPackage.RUN_COMMAND_ARGUMENTS", request.arguments.toTypedArray())
        request.stdin?.let { putExtra("$termuxPackage.RUN_COMMAND_STDIN", it) }
        putExtra("$termuxPackage.RUN_COMMAND_WORKDIR", request.workingDirectory)
        putExtra("$termuxPackage.RUN_COMMAND_BACKGROUND", true)
        putExtra("$termuxPackage.RUN_COMMAND_BACKGROUND_CUSTOM_LOG_LEVEL", 0)
        putExtra("$termuxPackage.RUN_COMMAND_PENDING_INTENT", pendingIntent)
        putExtra("$termuxPackage.RUN_COMMAND_COMMAND_LABEL", "Operit host command")
    }
}

internal class NativeTermuxSessionTransport(
    private val transport: NativeTermuxCommandTransport,
    private val termuxPackage: String = EXTERNAL_TERMUX_PACKAGE,
) : TermuxSessionTransport {
    override val termuxPrefix: String = "/data/data/$termuxPackage/files/usr"
    override val termuxHome: String = "/data/data/$termuxPackage/files/home"

    override suspend fun executeProgram(
        program: String,
        arguments: List<String>,
        stdin: String?,
        timeoutMs: Long,
    ): TermuxTransportResult {
        require(program.matches(Regex("^[A-Za-z0-9._+-]+$"))) {
            "Termux program must be relative to PREFIX/bin"
        }
        val result = try {
            transport.execute(
                NativeTermuxCommandRequest(
                    command = (listOf(program) + arguments).joinToString(" "),
                    executable = "$termuxPrefix/bin/env",
                    arguments = listOf(
                        "HOME=$termuxHome",
                        "PREFIX=$termuxPrefix",
                        "PATH=$termuxHome/.pi/bin:$termuxHome/.local/bin:$termuxPrefix/bin:/system/bin:/system/xbin",
                        "$termuxPrefix/bin/$program",
                    ) + arguments,
                    workingDirectory = termuxHome,
                    stdin = stdin,
                ),
                timeoutMs,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return TermuxTransportResult(
                exitCode = (error as? NativeTermuxTransportException)?.exitCode ?: 1,
                errCode = 1,
                errorMessage = error.message ?: error.javaClass.simpleName,
            )
        }
        return TermuxTransportResult(
            stdout = result.stdout,
            stderr = result.stderr,
            exitCode = result.exitCode,
            errCode = result.errorCode,
            errorMessage = result.errorMessage,
            timedOut = result.timedOut,
        )
    }
}

internal class NativeTermuxTransportException(
    val exitCode: Int,
    message: String,
) : IllegalStateException(message)

internal object NativeProcessNameResolver {
    fun currentProcessName(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { Application.getProcessName() }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
                ?.let { return it }
        }
        val pid = Process.myPid()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        activityManager?.runningAppProcesses
            ?.firstOrNull { it.pid == pid }
            ?.processName
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
        return runCatching {
            File("/proc/self/cmdline").readText().substringBefore('\u0000').trim()
        }.getOrNull().orEmpty().ifBlank { context.packageName }
    }
}
