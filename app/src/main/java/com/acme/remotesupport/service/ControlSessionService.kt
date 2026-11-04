package com.acme.remotesupport.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.acme.remotesupport.R
import com.acme.remotesupport.RemoteApp

/**
 * CONTROLLER side foreground keep-alive. The actual peer connection and video
 * rendering live in the RemoteControl screen's ViewModel; this service exists so
 * the OS keeps the session alive (audio playback type) while the controller views
 * the remote device, and so the session shows an ongoing notification.
 */
class ControlSessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val target = intent?.getStringExtra(EXTRA_TARGET_NAME) ?: "device"
        val n: Notification = NotificationCompat.Builder(this, RemoteApp.CH_CONTROL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Connected to $target")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, n)
        return START_NOT_STICKY
    }

    companion object {
        private const val NOTIF_ID = 43
        const val EXTRA_TARGET_NAME = "target_name"

        fun start(context: Context, targetName: String) {
            context.startForegroundService(
                Intent(context, ControlSessionService::class.java)
                    .putExtra(EXTRA_TARGET_NAME, targetName),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ControlSessionService::class.java))
        }
    }
}
