package com.acme.remotesupport.data.local

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * TARGET side. Remembers whether this phone offers unattended access and, if so, the
 * salted PBKDF2 hash of the owner's PIN. The PIN itself is never stored, and the hash
 * never leaves the device — a controller proves knowledge of the PIN over the encrypted
 * data channel ([com.acme.remotesupport.data.webrtc.ControlEvent.Auth]) and the target
 * verifies it here.
 *
 * This is the credential that replaces a live, per-session "Accept?" tap: the owner
 * enrols once (sets the PIN), and afterwards a trusted controller that presents the
 * PIN may reconnect without the app's own consent dialog. It does NOT remove Android's
 * own screen-capture prompt, which the OS still shows once per projection grant.
 */
object UnattendedAccess {
    private const val PREFS = "unattended_access"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_HASH = "pin_hash"
    private const val KEY_SALT = "pin_salt"
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false) && prefs(context).contains(KEY_HASH)

    /** Enrol: store a fresh salt + PBKDF2 hash of [pin] and turn unattended access on. */
    fun setPin(context: Context, pin: String) {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(pin, salt)
        prefs(context).edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .putBoolean(KEY_ENABLED, true)
            .apply()
    }

    /** Turn unattended access off and forget the stored hash. */
    fun disable(context: Context) {
        prefs(context).edit().remove(KEY_HASH).remove(KEY_SALT).putBoolean(KEY_ENABLED, false).apply()
    }

    /** Constant-time check of [pin] against the stored hash. */
    fun verify(context: Context, pin: String): Boolean {
        val p = prefs(context)
        val saltB64 = p.getString(KEY_SALT, null) ?: return false
        val hashB64 = p.getString(KEY_HASH, null) ?: return false
        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        val expected = Base64.decode(hashB64, Base64.NO_WRAP)
        val actual = pbkdf2(pin, salt)
        return constantTimeEquals(expected, actual)
    }

    private fun pbkdf2(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
