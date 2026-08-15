package com.wuxianpi.openhouse.feature.pages

import android.util.AtomicFile
import java.io.File

internal object OpenHousePageFiles {
    fun read(file: File): String? = runCatching {
        String(AtomicFile(file).readFully(), Charsets.UTF_8)
    }.getOrNull()

    fun write(file: File, value: String) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(value.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }
}
