package com.ai.assistance.operit.ui.common.markdown

import android.content.Context
import android.graphics.Typeface
import com.ai.assistance.operit.core.application.OperitApplication

private object MarkdownCodeTypefaceCache {
    @Volatile
    private var cachedTypeface: Typeface? = null

    fun get(context: Context): Typeface {
        cachedTypeface?.let { return it }

        return synchronized(this) {
            cachedTypeface
                ?: Typeface.MONOSPACE.also { cachedTypeface = it }
        }
    }
}

internal fun getMarkdownCodeTypeface(context: Context): Typeface {
    return MarkdownCodeTypefaceCache.get(context)
}

internal fun getMarkdownCodeTypeface(): Typeface {
    return getMarkdownCodeTypeface(OperitApplication.instance.applicationContext)
}
