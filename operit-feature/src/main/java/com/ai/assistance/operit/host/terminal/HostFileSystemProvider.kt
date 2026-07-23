package com.ai.assistance.operit.host.terminal

import android.util.Base64
import com.ai.assistance.operit.host.OperitHostProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HostFileSystemProvider {
    data class WriteResult(
        val success: Boolean,
        val message: String
    )

    data class FileInfo(
        val name: String,
        val isDirectory: Boolean,
        val size: Long = 0,
        val permissions: String = "",
        val lastModified: String = ""
    )

    suspend fun exists(path: String): Boolean {
        return runTest("[ -e ${HostTerminalPolicy.shellQuote(path)} ]")
    }

    suspend fun isDirectory(path: String): Boolean {
        return runTest("[ -d ${HostTerminalPolicy.shellQuote(path)} ]")
    }

    suspend fun isFile(path: String): Boolean {
        return runTest("[ -f ${HostTerminalPolicy.shellQuote(path)} ]")
    }

    suspend fun getFileSize(path: String): Long {
        val result = runCommand("stat -c %s ${HostTerminalPolicy.shellQuote(path)}")
        return result.stdout.trim().toLongOrNull() ?: 0L
    }

    suspend fun readFile(path: String): String? {
        return readFileBytes(path)?.let { String(it, Charsets.UTF_8) }
    }

    suspend fun readFileWithLimit(path: String, maxBytes: Int): String? {
        return readFileSample(path, maxBytes)?.let { String(it, Charsets.UTF_8) }
    }

    suspend fun readFileLines(path: String, startLine: Int, endLine: Int): String? {
        val safeStartLine = startLine.coerceAtLeast(1)
        val safeEndLine = endLine.coerceAtLeast(safeStartLine)
        val command = "sed -n '${safeStartLine},${safeEndLine}p' ${HostTerminalPolicy.shellQuote(path)}"
        val result = runCommand(command)
        return if (result.isSuccess) result.stdout else null
    }

    suspend fun readFileSample(path: String, sampleSize: Int): ByteArray? {
        val safeSampleSize = sampleSize.coerceAtLeast(0)
        val command = "head -c $safeSampleSize ${HostTerminalPolicy.shellQuote(path)} | base64"
        val result = runCommand(command)
        return if (result.isSuccess) decodeBase64Bytes(result.stdout) else null
    }

    suspend fun readFileBytes(path: String): ByteArray? {
        val result = runCommand("base64 ${HostTerminalPolicy.shellQuote(path)}")
        return if (result.isSuccess) decodeBase64Bytes(result.stdout) else null
    }

    suspend fun getLineCount(path: String): Int {
        val result = runCommand("wc -l < ${HostTerminalPolicy.shellQuote(path)}")
        return result.stdout.trim().toIntOrNull() ?: 0
    }

    suspend fun writeFile(path: String, content: String, append: Boolean = false): WriteResult {
        return writeEncodedFile(
            path = path,
            encoded = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
            append = append,
            successMessage = if (append) "File appended" else "File written"
        )
    }

    suspend fun writeFileBytes(path: String, bytes: ByteArray): WriteResult {
        return writeEncodedFile(
            path = path,
            encoded = Base64.encodeToString(bytes, Base64.NO_WRAP),
            append = false,
            successMessage = "Binary file written"
        )
    }

    suspend fun createDirectory(path: String, createParents: Boolean = true): WriteResult {
        val flag = if (createParents) "-p " else ""
        val result = runCommand("mkdir $flag${HostTerminalPolicy.shellQuote(path)}")
        return operationResult(result, "Directory created")
    }

    suspend fun delete(path: String, recursive: Boolean = false): WriteResult {
        if (path.isBlank() || path == "/") {
            return WriteResult(success = false, message = "Refusing to delete root or blank path")
        }
        val flag = if (recursive) "-rf" else "-f"
        val result = runCommand("rm $flag ${HostTerminalPolicy.shellQuote(path)}")
        return operationResult(result, "Path deleted")
    }

    suspend fun move(sourcePath: String, destPath: String): WriteResult {
        val quotedDestPath = HostTerminalPolicy.shellQuote(destPath)
        val command =
            "mkdir -p \"\$(dirname $quotedDestPath)\" && mv ${HostTerminalPolicy.shellQuote(sourcePath)} $quotedDestPath"
        val result = runCommand(command)
        return operationResult(result, "Path moved")
    }

    suspend fun copy(sourcePath: String, destPath: String, recursive: Boolean = true): WriteResult {
        val quotedDestPath = HostTerminalPolicy.shellQuote(destPath)
        val flag = if (recursive) "-R " else ""
        val command =
            "mkdir -p \"\$(dirname $quotedDestPath)\" && cp ${flag}${HostTerminalPolicy.shellQuote(sourcePath)} $quotedDestPath"
        val result = runCommand(command)
        return operationResult(result, "Path copied")
    }

    suspend fun listDirectory(path: String): List<FileInfo>? {
        val quotedPath = HostTerminalPolicy.shellQuote(path)
        val command =
            "p=$quotedPath; " +
                "for entry in \"\$p\"/* \"\$p\"/.[!.]* \"\$p\"/..?*; do " +
                "[ -e \"\$entry\" ] || continue; " +
                "name=\${entry##*/}; " +
                "type=f; [ -d \"\$entry\" ] && type=d; " +
                "size=\$(stat -c %s \"\$entry\" 2>/dev/null || printf 0); " +
                "perms=\$(stat -c %A \"\$entry\" 2>/dev/null || printf ''); " +
                "modified=\$(stat -c %y \"\$entry\" 2>/dev/null || printf ''); " +
                "name64=\$(printf %s \"\$name\" | base64 | tr -d '\\n'); " +
                "printf '%s\\t%s\\t%s\\t%s\\t%s\\n' \"\$name64\" \"\$type\" \"\$size\" \"\$perms\" \"\$modified\"; " +
                "done"
        val result = runCommand(command)
        if (!result.isSuccess) return null
        return result.stdout
            .lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { parseFileInfoLine(it) }
            .toList()
    }

    suspend fun findFiles(
        basePath: String,
        pattern: String,
        maxDepth: Int = -1,
        caseInsensitive: Boolean = false
    ): List<String> {
        val depthArg = if (maxDepth >= 0) "-maxdepth ${maxDepth + 1} " else ""
        val nameArg = if (caseInsensitive) "-iname" else "-name"
        val command =
            "find ${HostTerminalPolicy.shellQuote(basePath)} -mindepth 1 $depthArg$nameArg ${HostTerminalPolicy.shellQuote(pattern)} " +
                "-exec sh -c 'for p do printf \"%s\\n\" \"\$(printf %s \"\$p\" | base64 | tr -d \"\\n\")\"; done' sh {} +"
        val result = runCommand(command)
        if (!result.isSuccess) return emptyList()
        return result.stdout
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { decodeBase64Text(it) }
            .toList()
    }

    suspend fun getFileInfo(path: String): FileInfo? {
        val quotedPath = HostTerminalPolicy.shellQuote(path)
        val command =
            "p=$quotedPath; " +
                "[ -e \"\$p\" ] || exit 1; " +
                "trimmed=\${p%/}; [ -n \"\$trimmed\" ] || trimmed=/; " +
                "name=\${trimmed##*/}; [ -n \"\$name\" ] || name=/; " +
                "type=f; [ -d \"\$p\" ] && type=d; " +
                "size=\$(stat -c %s \"\$p\" 2>/dev/null || printf 0); " +
                "perms=\$(stat -c %A \"\$p\" 2>/dev/null || printf ''); " +
                "modified=\$(stat -c %y \"\$p\" 2>/dev/null || printf ''); " +
                "name64=\$(printf %s \"\$name\" | base64 | tr -d '\\n'); " +
                "printf '%s\\t%s\\t%s\\t%s\\t%s\\n' \"\$name64\" \"\$type\" \"\$size\" \"\$perms\" \"\$modified\""
        val result = runCommand(command)
        if (!result.isSuccess) return null
        return result.stdout
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.let { parseFileInfoLine(it) }
    }

    private suspend fun runTest(script: String): Boolean {
        return runCommand(script).isSuccess
    }

    private suspend fun runCommand(command: String) =
        withContext(Dispatchers.IO) {
            OperitHostProvider.operationsOrUnsupported().executeCommand(
                command = command,
                target = HostTerminalTarget.TERMUX,
                timeoutMs = COMMAND_TIMEOUT_MS,
            )
        }

    private suspend fun writeEncodedFile(
        path: String,
        encoded: String,
        append: Boolean,
        successMessage: String
    ): WriteResult {
        val quotedPath = HostTerminalPolicy.shellQuote(path)
        val redirect = if (append) ">>" else ">"
        val command =
            "mkdir -p \"\$(dirname $quotedPath)\" && printf %s ${HostTerminalPolicy.shellQuote(encoded)} | base64 -d $redirect $quotedPath"
        val result = runCommand(command)
        return operationResult(result, successMessage)
    }

    private fun operationResult(
        result: com.ai.assistance.operit.host.OperitHostCommandResult,
        successMessage: String
    ): WriteResult {
        return WriteResult(
            success = result.isSuccess,
            message =
                if (result.isSuccess) {
                    result.stdout.ifBlank { successMessage }
                } else {
                    result.stderr.ifBlank {
                        result.error.ifBlank {
                            result.stdout.ifBlank { "Command failed with exit code ${result.exitCode}" }
                        }
                    }
                }
        )
    }

    private fun parseFileInfoLine(line: String): FileInfo? {
        val parts = line.split('\t', limit = 5)
        if (parts.size < 5) return null
        val name = decodeBase64Text(parts[0]) ?: return null
        return FileInfo(
            name = name,
            isDirectory = parts[1] == "d",
            size = parts[2].trim().toLongOrNull() ?: 0L,
            permissions = parts[3],
            lastModified = parts[4]
        )
    }

    private fun decodeBase64Text(value: String): String? {
        return decodeBase64Bytes(value)?.let { String(it, Charsets.UTF_8) }
    }

    private fun decodeBase64Bytes(value: String): ByteArray? {
        val normalized = value.trim()
        if (normalized.isEmpty()) return ByteArray(0)
        return try {
            Base64.decode(normalized, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    companion object {
        private const val COMMAND_TIMEOUT_MS = 60_000L
    }
}
