package com.acme.remotesupport.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.acme.remotesupport.ui.AppCoordinator

/**
 * Transparent activity that shows the OS screen-capture consent dialog. The user
 * must approve here every time capture starts — this is the explicit consent gate
 * we deliberately keep (the reference app auto-accepted it; we do not).
 */
class RequestCaptureActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        val controller = intent.getStringExtra(EXTRA_CONTROLLER_DEVICE_ID)
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenCaptureService.startWithProjection(this, result.resultCode, result.data!!, sessionId, controller)
        } else if (sessionId != null) {
            // The user cancelled the system prompt: don't leave the controller waiting.
            AppCoordinator.decline(sessionId, controller)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // Prompt already showing; don't ask twice.
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        launcher.launch(mpm.createScreenCaptureIntent())
    }

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_CONTROLLER_DEVICE_ID = "controller_device_id"

        fun request(context: Context, sessionId: String?, controllerDeviceId: String?) {
            val i = Intent(context, RequestCaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_CONTROLLER_DEVICE_ID, controllerDeviceId)
            }
            context.startActivity(i)
        }
    }
}
