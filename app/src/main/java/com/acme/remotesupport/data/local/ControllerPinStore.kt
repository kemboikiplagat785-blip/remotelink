package com.acme.remotesupport.data.local

import android.content.Context

/**
 * CONTROLLER side. Remembers the unattended-access PIN for each target device so the
 * owner connects without re-typing it. Each PIN is encrypted with a Keystore-held key
 * ([KeystoreCrypto]) and keyed by the target's account-scoped device id, so the stored
 * value is useless if the prefs file is copied off the device.
 *
 * A rejected PIN is removed by the caller so the next connect prompts again.
 */
object ControllerPinStore {
    private const val PREFS = "controller_pins"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, targetDeviceId: String): String? =
        prefs(context).getString(targetDeviceId, null)?.let { KeystoreCrypto.decrypt(it) }

    fun put(context: Context, targetDeviceId: String, pin: String) {
        val enc = KeystoreCrypto.encrypt(pin) ?: return
        prefs(context).edit().putString(targetDeviceId, enc).apply()
    }

    fun remove(context: Context, targetDeviceId: String) {
        prefs(context).edit().remove(targetDeviceId).apply()
    }
}
