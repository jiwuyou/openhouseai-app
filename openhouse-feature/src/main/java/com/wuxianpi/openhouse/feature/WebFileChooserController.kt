package com.wuxianpi.openhouse.feature

import android.content.Intent
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

/** Owns the Activity Result lifecycle for one WebView file chooser request. */
class WebFileChooserController(
    activity: ComponentActivity,
) : WebFileChooserHost {
    private var pendingCallback: ValueCallback<Array<Uri>>? = null
    private var pendingParams: WebChromeClient.FileChooserParams? = null
    private val launcher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = pendingCallback
        val params = pendingParams
        pendingCallback = null
        pendingParams = null
        if (callback == null) return@registerForActivityResult
        callback.onReceiveValue(
            if (params == null) null
            else WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        )
    }

    override fun launchWebFileChooser(
        params: WebChromeClient.FileChooserParams,
        callback: ValueCallback<Array<Uri>>,
    ) {
        cancelWebFileChooser()
        pendingCallback = callback
        pendingParams = params
        val intent = runCatching { params.createIntent() }.getOrElse {
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
            }
        }.apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        runCatching { launcher.launch(intent) }
            .onFailure { cancelWebFileChooser() }
    }

    override fun cancelWebFileChooser() {
        pendingCallback?.onReceiveValue(null)
        pendingCallback = null
        pendingParams = null
    }
}
