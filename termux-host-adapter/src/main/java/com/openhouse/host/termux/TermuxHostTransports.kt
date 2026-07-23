package com.openhouse.host.termux

import com.ai.assistance.operit.host.OperitHostCommandResult
import com.ai.assistance.operit.host.terminal.tmux.TermuxSessionTransport
import com.ai.assistance.operit.host.terminal.tmux.TermuxTransportResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Executes Android-targeted tools only through the Android system shell. */
internal class TermuxAndroidShellCommandExecutor(
    private val workingDirectory: File,
    private val shell: File = File("/system/bin/sh"),
    private val runner: ConcurrentProcessRunner = ConcurrentProcessRunner(),
) {
    fun execute(command: String, timeoutMs: Long): OperitHostCommandResult {
        val startedAt = System.currentTimeMillis()
        val cleanCommand = command.trim()
        if (cleanCommand.isEmpty()) return failure(command, 2, "command is empty", startedAt)
        if (!shell.isFile) {
            return failure(command, 127, "Android shell is unavailable: ${shell.absolutePath}", startedAt)
        }
        return runner.run(
            originalCommand = command,
            processBuilder = ProcessBuilder(shell.absolutePath, "-c", cleanCommand)
                .directory(workingDirectory)
                .redirectErrorStream(false),
            timeoutMs = timeoutMs.coerceAtLeast(1L),
            startedAt = startedAt,
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

/** Runs the shared tmux protocol directly inside the embedded Termux userspace. */
internal class EmbeddedTermuxSessionTransport(
    private val layout: TermuxRuntimeLayout,
    private val runner: ConcurrentProcessRunner = ConcurrentProcessRunner(),
) : TermuxSessionTransport {
    override val termuxPrefix: String = layout.prefix.absolutePath

    override suspend fun executeProgram(
        program: String,
        arguments: List<String>,
        stdin: String?,
        timeoutMs: Long,
    ): TermuxTransportResult = withContext(Dispatchers.IO) {
        require(program.matches(Regex("^[A-Za-z0-9._+-]+$"))) {
            "Termux program must be relative to PREFIX/bin"
        }
        val executable = File(layout.prefix, "bin/$program")
        if (!executable.isFile) {
            return@withContext TermuxTransportResult(
                exitCode = 127,
                errCode = 1,
                errorMessage = "Termux program is unavailable: ${executable.absolutePath}",
            )
        }
        val processBuilder = ProcessBuilder(listOf(executable.absolutePath) + arguments)
            .directory(layout.home)
            .redirectErrorStream(false)
        configureEnvironment(processBuilder.environment())
        val result = runner.run(
            originalCommand = (listOf(program) + arguments).joinToString(" "),
            processBuilder = processBuilder,
            timeoutMs = timeoutMs,
            stdin = stdin,
        )
        TermuxTransportResult(
            stdout = result.stdout,
            stderr = result.stderr,
            exitCode = result.exitCode,
            errCode = if (result.timedOut || (result.exitCode == 0 && result.error.isNotBlank())) 1 else -1,
            errorMessage = result.error,
            timedOut = result.timedOut,
        )
    }

    private fun configureEnvironment(environment: MutableMap<String, String>) {
        environment["HOME"] = layout.home.absolutePath
        environment["PREFIX"] = layout.prefix.absolutePath
        environment["PATH"] = prependPath(
            environment["PATH"],
            File(layout.prefix, "bin").absolutePath,
            "/system/bin",
        )
        environment["LD_LIBRARY_PATH"] = prependPath(
            environment["LD_LIBRARY_PATH"],
            File(layout.prefix, "lib").absolutePath,
        )
        environment["TMPDIR"] = File(layout.prefix, "tmp").absolutePath
        if (environment["LANG"].isNullOrBlank()) environment["LANG"] = "C.UTF-8"
    }

    private fun prependPath(original: String?, vararg entries: String): String {
        val values = original.orEmpty().split(File.pathSeparator).filter(String::isNotBlank).toMutableList()
        entries.reversed().forEach { entry ->
            values.remove(entry)
            values.add(0, entry)
        }
        return values.joinToString(File.pathSeparator)
    }
}
