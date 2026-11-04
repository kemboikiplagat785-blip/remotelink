package com.acme.remotesupport.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.acme.remotesupport.MainActivity
import com.acme.remotesupport.R
import com.acme.remotesupport.RemoteApp
import com.acme.remotesupport.data.local.UnattendedAccess

/**
 * On reboot we deliberately do NOT restart screen capture: Android requires a fresh,
 * user-approved MediaProjection token every time, and auto-accepting that prompt is
 * exactly the covert behaviour this app does not reproduce.
 *
 * Instead, if the owner enrolled this device for unattended access, we post a single
 * notification so they can resume it with one tap — which opens the app and shows the
 * normal system screen-capture prompt. No capture happens until they do.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!UnattendedAccess.isEnabled(context)) return

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_RESUME_CAPTURE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, RemoteApp.CH_CAPTURE)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.unattended_resume_prompt))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        androidx.core.app.NotificationManagerCompat.from(context).apply {
            if (areNotificationsEnabled()) notify(RESUME_NOTIF_ID, n)
        }
    }

    private companion object {
        const val RESUME_NOTIF_ID = 44
    }
}
