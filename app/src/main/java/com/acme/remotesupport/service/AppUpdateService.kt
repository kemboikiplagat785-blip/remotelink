package com.acme.remotesupport.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.acme.remotesupport.R
import com.acme.remotesupport.RemoteApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Downloads a newer APK (hosted by you, e.g. in Supabase storage) and launches
 * the system package installer. This is for out-of-store distribution; if you ship
 * on Google Play, remove this and REQUEST_INSTALL_PACKAGES entirely.
 */
class AppUpdateService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)
        val version = intent?.getStringExtra(EXTRA_VERSION) ?: "latest"
        startForeground(NOTIF_ID, buildNotification("Downloading update…"))
        if (url == null) { stopSelf(); return START_NOT_STICKY }
        scope.launch {
            runCatching { downloadAndInstall(url, version) }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun downloadAndInstall(url: String, version: String) {
        val dir = File(cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "update-$version.apk")
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return
            resp.body?.byteStream()?.use { input ->
                apk.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(install)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, RemoteApp.CH_UPDATE)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        private const val NOTIF_ID = 44
        const val EXTRA_URL = "url"
        const val EXTRA_VERSION = "version"
        private const val PREFS = "updates"
        private const val KEY_SKIPPED = "skipped_version"

        fun start(context: Context, url: String, version: String) {
            context.startForegroundService(
                Intent(context, AppUpdateService::class.java)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_VERSION, version),
            )
        }

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun markSkipped(context: Context, version: String) {
            prefs(context).edit().putString(KEY_SKIPPED, version).apply()
        }

        fun isSkipped(context: Context, version: String): Boolean =
            prefs(context).getString(KEY_SKIPPED, null) == version
    }
}
