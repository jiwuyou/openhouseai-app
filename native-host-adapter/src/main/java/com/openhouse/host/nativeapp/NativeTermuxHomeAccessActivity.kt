package com.openhouse.host.nativeapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.widget.TextView

/** Requests and persists exactly the external Termux Home SAF tree. */
class NativeTermuxHomeAccessActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 17f
            setPadding(48, 48, 48, 48)
            text = "Select Termux Home and choose Use this folder."
        }
        setContentView(status)
        if (savedInstanceState == null) openPicker()
    }

    @Deprecated("Activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_TERMUX_HOME) return
        val uri = data?.data
        if (resultCode != RESULT_OK || !isValidatedTermuxHomeTree(uri)) {
            status.text = "The selected folder is not Termux Home. Please select the Termux Home root."
            return
        }
        val requestedFlags = (data?.flags ?: 0) and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        runCatching {
            contentResolver.takePersistableUriPermission(
                requireNotNull(uri),
                requestedFlags,
            )
        }.onSuccess {
            status.text = "Termux Home access granted."
            finish()
        }.onFailure { error ->
            status.text = error.message ?: "Unable to persist Termux Home access."
        }
    }

    private fun openPicker() {
        val initialUri = DocumentsContract.buildRootUri(TERMUX_DOCUMENTS_AUTHORITY, "termux-home")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
            putExtra("android.provider.extra.INITIAL_URI", initialUri)
        }, REQUEST_TERMUX_HOME)
    }

    companion object {
        private const val REQUEST_TERMUX_HOME = 7101
    }
}
