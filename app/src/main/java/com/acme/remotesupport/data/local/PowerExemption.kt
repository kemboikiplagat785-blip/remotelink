package com.acme.remotesupport.data.local

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Asks Android to exempt this app from battery optimization.
 *
 * Unattended access only stays promptless while the capture foreground service keeps
 * running; aggressive battery management (especially on Samsung/One UI) will otherwise
 * kill it after a while, and the next reconnect then needs a fresh screen-capture tap
 * that nobody is there to give. The exemption makes the service survive an idle phone.
 * It does NOT grant any capture ability — Android's own capture prompt is unaffected.
 */
object PowerExemption {

    fun isIgnoring(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Opens the system dialog to exempt this app, unless it already is exempt. */
    @SuppressLint("BatteryLife") // Justified: unattended access needs a long-lived service.
    fun request(context: Context) {
        if (isIgnoring(context)) return
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
