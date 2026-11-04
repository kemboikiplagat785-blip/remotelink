package com.acme.remotesupport.data

import com.acme.remotesupport.data.repository.AuthRepository
import com.acme.remotesupport.data.repository.BillingRepository
import com.acme.remotesupport.data.repository.DeviceRepository
import com.acme.remotesupport.data.repository.MiscRepository
import com.acme.remotesupport.data.repository.SessionRepository
import com.acme.remotesupport.data.repository.SubscriptionRepository
import com.acme.remotesupport.data.webrtc.SignalingRepository

/**
 * Minimal manual DI. The owner-scoped repositories are rebuilt whenever the
 * signed-in user changes. Call [bind] after auth, [clear] on sign-out.
 */
object ServiceLocator {
    val auth = AuthRepository()
    val billing = BillingRepository()

    @Volatile private var ownerId: String? = null
    @Volatile private var _devices: DeviceRepository? = null
    @Volatile private var _sessions: SessionRepository? = null
    @Volatile private var _signaling: SignalingRepository? = null
    @Volatile private var _subscription: SubscriptionRepository? = null
    @Volatile private var _misc: MiscRepository? = null

    fun bind(uid: String) {
        if (ownerId == uid) return
        ownerId = uid
        _devices = DeviceRepository(uid)
        _sessions = SessionRepository(uid)
        _signaling = SignalingRepository(uid)
        _subscription = SubscriptionRepository(uid)
        _misc = MiscRepository(uid)
    }

    fun clear() {
        ownerId = null
        _devices = null; _sessions = null; _signaling = null
        _subscription = null; _misc = null
    }

    private fun <T> require(value: T?): T =
        value ?: error("ServiceLocator not bound — call bind(uid) after sign-in")

    val devices: DeviceRepository get() = require(_devices)
    val sessions: SessionRepository get() = require(_sessions)
    val signaling: SignalingRepository get() = require(_signaling)
    val subscription: SubscriptionRepository get() = require(_subscription)
    val misc: MiscRepository get() = require(_misc)
}
