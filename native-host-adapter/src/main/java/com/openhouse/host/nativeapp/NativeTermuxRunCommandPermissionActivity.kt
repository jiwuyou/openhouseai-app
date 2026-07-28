package com.openhouse.host.nativeapp

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Requests RUN_COMMAND and proves the external Termux command path with a real command. */
class NativeTermuxRunCommandPermissionActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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
            testRunCommand()
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
            testRunCommand()
        } else {
            status.text = "Termux RUN_COMMAND permission was not granted."
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun testRunCommand() {
        status.text = "Testing external Termux command access..."
        scope.launch {
            val result = ExternalTermuxCommandExecutor(NativeTermuxRunCommandTransport(this@NativeTermuxRunCommandPermissionActivity))
                .execute("printf 'wuxianpi-termux-ready'", ExternalTermuxCommandTarget.TERMUX, 15_000L)
            if (result.isSuccess && result.stdout.contains("wuxianpi-termux-ready")) {
                status.text = "External Termux command access is ready."
                finish()
            } else {
                status.text = result.error.ifBlank { result.stderr }.ifBlank {
                    "Termux command test failed. Check allow-external-apps in Termux."
                }
            }
        }
    }

    companion object {
        const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
        private const val REQUEST_RUN_COMMAND = 7102
    }
}
