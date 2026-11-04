package com.acme.remotesupport.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fired from the update notification's "Skip this version" action. */
class UpdateSkipReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val version = intent.getStringExtra(EXTRA_VERSION) ?: return
        AppUpdateService.markSkipped(context, version)
    }

    companion object {
        const val EXTRA_VERSION = "version"
    }
}
