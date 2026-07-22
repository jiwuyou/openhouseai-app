package com.ai.assistance.operit.pi

import android.content.Context
import android.os.Build
import com.wuxianpi.pi.PiNodeDiagnosticsExport
import com.wuxianpi.pi.PiNodeDiagnosticsStatus
import com.wuxianpi.pi.PiRuntimeReady
import com.wuxianpi.pi.RollingJsonlDiagnostics
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** Shared Android-side diagnostics for Basic Pi and host setup screens. */
object OperitPiDiagnostics {
    @Volatile private var sink: RollingJsonlDiagnostics? = null

    fun get(context: Context): RollingJsonlDiagnostics = sink ?: synchronized(this) {
        sink ?: RollingJsonlDiagnostics(
            File(context.applicationContext.filesDir, "operit-pi-diagnostics"),
        ).also { sink = it }
    }

    fun record(context: Context, event: String, details: Map<String, Any?> = emptyMap()) {
        get(context).record(event, details)
    }

    fun exportZip(
        context: Context,
        node: PiNodeDiagnosticsExport?,
        nodeStatus: PiNodeDiagnosticsStatus?,
        ready: PiRuntimeReady?,
        nodeError: String? = null,
    ): File {
        val logger = get(context)
        val directory = File(context.cacheDir, "operit-pi-diagnostics").apply { mkdirs() }
        val output = File(directory, "operit-pi-diagnostics-${System.currentTimeMillis()}.zip")
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val metadata = JSONObject()
            .put("createdAt", System.currentTimeMillis())
            .put("packageName", context.packageName)
            .put("versionName", packageInfo?.versionName)
            .put("versionCode", packageInfo?.longVersionCode)
            .put("androidSdk", Build.VERSION.SDK_INT)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            .put("connectionId", ready?.connectionId)
            .put("runtimeVersion", ready?.version)
            .put("eventAck", ready?.capabilities?.eventAck)
            .put("persistentDiagnostics", ready?.capabilities?.persistentDiagnostics)
            .put("androidDroppedEntries", logger.droppedEntries())
            .put("androidDetailedUntil", logger.detailedUntilMillis())
            .put("nodePath", node?.path ?: nodeStatus?.path)
            .put("nodeSize", node?.size ?: nodeStatus?.size)
            .put("nodeExportError", nodeError)
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            zip.writeEntry("android.jsonl", logger.snapshotJsonl())
            zip.writeEntry("node.jsonl", node?.content.orEmpty().toByteArray())
            zip.writeEntry("metadata.json", metadata.toString(2).toByteArray())
        }
        return output
    }

    private fun ZipOutputStream.writeEntry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }
}
