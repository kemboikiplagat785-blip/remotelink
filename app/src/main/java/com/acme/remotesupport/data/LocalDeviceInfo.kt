package com.acme.remotesupport.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.acme.remotesupport.BuildConfig
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.util.UUID

private val Context.deviceStore by preferencesDataStore(name = "device_identity")

/** Identity for this device that survives reinstalls, plus static hardware facts. */
object LocalDeviceInfo {
    private val CODE = stringPreferencesKey("device_code")

    val manufacturer: String get() = Build.MANUFACTURER
    val model: String get() = Build.MODEL
    val osVersion: String get() = "Android ${Build.VERSION.RELEASE}"
    val appVersion: String get() = BuildConfig.VERSION_NAME
    val defaultName: String get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    /**
     * The code this device registers under. Derived from ANDROID_ID, which survives a
     * reinstall, so reinstalling can't leave this phone listed as a second device. Hashed
     * because the code is stored server-side and the raw id has no business being there.
     */
    @SuppressLint("HardwareIds")
    suspend fun deviceCode(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        if (androidId.isNullOrBlank()) return storedCode(context) ?: generateCode(context)
        val hash = MessageDigest.getInstance("SHA-256").digest(androidId.toByteArray())
        return hash.take(8).joinToString("") { "%02X".format(it) }
    }

    /** The random code earlier versions registered under, if this install has one. */
    suspend fun storedCode(context: Context): String? = context.deviceStore.data.first()[CODE]

    private suspend fun generateCode(context: Context): String {
        val code = UUID.randomUUID().toString().take(8).uppercase()
        context.deviceStore.edit { it[CODE] = code }
        return code
    }
}
