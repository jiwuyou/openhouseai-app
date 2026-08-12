package com.openhouse.host.nativeapp

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/** Requests Android's RUN_COMMAND permission. Command verification is a later explicit stage. */
class NativeTermuxRunCommandPermissionActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 17f
            setPadding(48, 48, 48, 48)
            text = "Requesting external Termux command access..."
        }
        setContentView(status)
        if (checkSelfPermission(RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            completeAuthorization()
        } else {
            requestPermissions(arrayOf(RUN_COMMAND_PERMISSION), REQUEST_RUN_COMMAND)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RUN_COMMAND) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            completeAuthorization()
        } else {
            status.text = "Termux RUN_COMMAND permission was not granted."
        }
    }

    private fun completeAuthorization() {
        status.text = "Termux RUN_COMMAND permission is granted."
        setResult(RESULT_OK)
        finish()
    }

    companion object {
        const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
        private const val REQUEST_RUN_COMMAND = 7102
    }
}
