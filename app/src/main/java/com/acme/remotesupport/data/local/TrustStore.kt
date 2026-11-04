package com.acme.remotesupport.data.local

import android.content.Context

/**
 * Remembers which controller devices this phone's user has chosen to always allow.
 *
 * Consent model: the user still grants this explicitly (the "Always allow" button),
 * and it only removes the app's own prompt — Android's screen-capture prompt is still
 * shown by the OS the first time capture starts after the app process restarts.
 * Keyed by the controller's account-scoped device id, so it never spans accounts.
 */
object TrustStore {
    private const val PREFS = "trusted_controllers"
    private const val KEY = "ids"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isTrusted(context: Context, controllerDeviceId: String?): Boolean {
        if (controllerDeviceId == null) return false
        return prefs(context).getStringSet(KEY, emptySet())!!.contains(controllerDeviceId)
    }

    fun trust(context: Context, controllerDeviceId: String?) {
        if (controllerDeviceId == null) return
        // getStringSet's result must not be mutated in place.
        val updated = HashSet(prefs(context).getStringSet(KEY, emptySet())!!)
        if (updated.add(controllerDeviceId)) {
            prefs(context).edit().putStringSet(KEY, updated).apply()
        }
    }

    fun revoke(context: Context, controllerDeviceId: String) {
        val updated = HashSet(prefs(context).getStringSet(KEY, emptySet())!!)
        if (updated.remove(controllerDeviceId)) {
            prefs(context).edit().putStringSet(KEY, updated).apply()
        }
    }
}
