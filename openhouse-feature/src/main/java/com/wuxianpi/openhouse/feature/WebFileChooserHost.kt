package com.wuxianpi.openhouse.feature

import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient

/** Activity-owned bridge for HTML input[type=file] requests from a WebView. */
interface WebFileChooserHost {
    fun launchWebFileChooser(
        params: WebChromeClient.FileChooserParams,
        callback: ValueCallback<Array<Uri>>,
    )

    fun cancelWebFileChooser() = Unit
}
