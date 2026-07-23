package com.openhouse.host.nativeapp

import com.ai.assistance.operit.host.OperitHostCommandResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** Executes commands only in Android's local shell. It never falls back to Termux. */
internal class AndroidShellCommandExecutor(
    private val shell: File = File("/system/bin/sh"),
    private val workingDirectory: File,
    private val runner: AndroidShellProcessRunner = AndroidShellProcessRunner(),
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
        code: Int,
        message: String,
        startedAt: Long,
    ) = OperitHostCommandResult(
        command = command,
        exitCode = code,
        stdout = "",
        stderr = "",
        error = message,
        timedOut = false,
        durationMs = System.currentTimeMillis() - startedAt,
    )
}

internal class AndroidShellProcessRunner {
    fun run(
        originalCommand: String,
        processBuilder: ProcessBuilder,
        timeoutMs: Long,
        startedAt: Long = System.currentTimeMillis(),
    ): OperitHostCommandResult = runCatching {
        val process = processBuilder.start()
        val stdout = AndroidShellStreamDrainer(process.inputStream, "android-shell-command-stdout")
        val stderr = AndroidShellStreamDrainer(process.errorStream, "android-shell-command-stderr")
        stdout.start()
        stderr.start()
        val finished = process.waitFor(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(200L, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        }
        val stdoutCapture = stdout.await()
        val stderrCapture = stderr.await()
        val exitCode = if (finished) process.exitValue() else 124
        val streamError = listOf(stdoutCapture.error, stderrCapture.error)
            .filter(String::isNotBlank)
            .joinToString("\n")
        val error = when {
            !finished -> listOf("command timed out after ${timeoutMs.coerceAtLeast(1L)} ms", streamError)
                .filter(String::isNotBlank)
                .joinToString("\n")
            exitCode != 0 -> stderrCapture.text.ifBlank { stdoutCapture.text }.ifBlank { streamError }
            streamError.isNotBlank() -> streamError
            else -> ""
        }
        OperitHostCommandResult(
            command = originalCommand,
            exitCode = exitCode,
            stdout = stdoutCapture.text,
            stderr = stderrCapture.text,
            error = error,
            timedOut = !finished,
            durationMs = System.currentTimeMillis() - startedAt,
        )
    }.getOrElse { error ->
        OperitHostCommandResult(
            command = originalCommand,
            exitCode = 1,
            stdout = "",
            stderr = "",
            error = error.message ?: error.javaClass.simpleName,
            timedOut = false,
            durationMs = System.currentTimeMillis() - startedAt,
        )
    }
}

private data class AndroidShellStreamCapture(
    val text: String,
    val error: String,
)

private class AndroidShellStreamDrainer(
    private val stream: InputStream,
    name: String,
) {
    private val output = ByteArrayOutputStream()
    private var failure: Throwable? = null
    private val thread = Thread({
        try {
            stream.use { input -> input.copyTo(output) }
        } catch (error: Throwable) {
            failure = error
        }
    }, name).apply { isDaemon = true }

    fun start() = thread.start()

    fun await(): AndroidShellStreamCapture {
        thread.join(2_000L)
        if (thread.isAlive) {
            runCatching { stream.close() }
            thread.join(250L)
        }
        return AndroidShellStreamCapture(
            output.toString(Charsets.UTF_8.name()),
            failure?.message.orEmpty(),
        )
    }
}
