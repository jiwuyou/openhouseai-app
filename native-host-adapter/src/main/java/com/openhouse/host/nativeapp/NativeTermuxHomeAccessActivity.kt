package com.openhouse.host.nativeapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
            CoroutineScope(Dispatchers.IO).launch {
                val result = runCatching {
                    NativeTermuxHomeRepository(applicationContext).registerAndProbe(requireNotNull(uri))
                }
                runOnUiThread {
                    result.onSuccess { readiness ->
                        if (isTermuxHomeWorkspaceReady(readiness)) {
                            status.text = "Termux Home attached as repo:termux-home."
                            finish()
                        } else {
                            status.text =
                                "Termux Home authorization is incomplete. Check access and try again."
                        }
                    }.onFailure { error ->
                        status.text = error.message ?: "Unable to attach Termux Home workspace."
                    }
                }
            }
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
