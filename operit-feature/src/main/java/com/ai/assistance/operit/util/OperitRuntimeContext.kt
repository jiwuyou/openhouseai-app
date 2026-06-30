package com.ai.assistance.operit.util

import android.content.Context
import androidx.annotation.StringRes
import com.ai.assistance.operit.host.OperitHostProvider
import java.io.File
import java.util.Locale

object OperitRuntimeContext {
    @Volatile
    private var boundContext: Context? = null

    private val startupTimeMs: Long = System.currentTimeMillis()

    @JvmStatic
    fun bind(context: Context) {
        boundContext = context.applicationContext
    }

    @JvmStatic
    fun currentOrNull(): Context? =
        OperitHostProvider.currentOrNull()?.applicationContext ?: boundContext

    @JvmStatic
    fun requireApplicationContext(): Context =
        currentOrNull() ?: error("Operit runtime context has not been bound by the host.")

    @JvmStatic
    fun startupTimeMs(): Long = startupTimeMs

    @JvmStatic
    fun getStringOrDefault(@StringRes resId: Int, defaultValue: String, vararg formatArgs: Any): String {
        val context = currentOrNull()
        if (context != null) {
            return runCatching {
                if (formatArgs.isEmpty()) {
                    context.getString(resId)
                } else {
                    context.getString(resId, *formatArgs)
                }
            }.getOrElse { formatDefault(defaultValue, formatArgs) }
        }
        return formatDefault(defaultValue, formatArgs)
    }

    @JvmStatic
    fun cacheDir(childName: String): File {
        val baseDir = currentOrNull()?.cacheDir
            ?: File(System.getProperty("java.io.tmpdir") ?: ".", "operit-cache").apply { mkdirs() }
        return File(baseDir, childName).apply { mkdirs() }
    }

    private fun formatDefault(defaultValue: String, formatArgs: Array<out Any>): String {
        if (formatArgs.isEmpty()) return defaultValue
        return runCatching { String.format(Locale.getDefault(), defaultValue, *formatArgs) }
            .getOrDefault(defaultValue)
    }
}
