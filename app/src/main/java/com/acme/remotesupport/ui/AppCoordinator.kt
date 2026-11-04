package com.acme.remotesupport.ui

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.acme.remotesupport.data.LocalDeviceInfo
import com.acme.remotesupport.data.ServiceLocator
import com.acme.remotesupport.data.local.TrustStore
import com.acme.remotesupport.data.local.UnattendedAccess
import com.acme.remotesupport.data.remote.DeviceDto
import com.acme.remotesupport.data.remote.NewDevice
import com.acme.remotesupport.data.remote.SessionDto
import com.acme.remotesupport.service.RequestCaptureActivity
import com.acme.remotesupport.service.ScreenCaptureService
import com.acme.remotesupport.ui.control.LiveWall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * App-wide glue that runs once the user is signed in:
 *  - binds the owner-scoped repositories,
 *  - registers THIS device so it appears in the grid and can be targeted,
 *  - (target role) watches for incoming session requests and exposes them so the
 *    UI can ask the user to consent before capture starts,
 *  - reports this device online while the app is in the foreground, which is when
 *    it can actually show that consent prompt.
 */
object AppCoordinator {
    private const val TAG = "AppCoordinator"
    private const val RETRY_DELAY_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _selfDeviceId = MutableStateFlow<String?>(null)
    val selfDeviceId: StateFlow<String?> = _selfDeviceId

    /** A pending session targeting this device, awaiting the user's consent. */
    private val _incoming = MutableStateFlow<SessionDto?>(null)
    val incoming: StateFlow<SessionDto?> = _incoming

    /** Sessions already answered, so a later reload of pending rows can't ask again. */
    private val answered = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var startedForUid: String? = null
    private var job: Job? = null

    val presenceObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) = reportPresence(true)
        override fun onStop(owner: LifecycleOwner) = reportPresence(false)
    }

    fun onAuthenticated(context: Context, uid: String) {
        if (startedForUid == uid) return
        startedForUid = uid
        ServiceLocator.bind(uid)
        val app = context.applicationContext
        job = scope.launch {
            // Retried because a failure here (e.g. offline at launch) would otherwise
            // leave the device unregistered until the app is restarted.
            while (isActive) {
                try {
                    registerAndWatch(app, uid)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Device registration or session watch failed", e)
                }
                delay(RETRY_DELAY_MS)
            }
        }
    }

    private suspend fun registerAndWatch(app: Context, uid: String) {
        val dto = registerSelf(app, uid)

        // Watch for sessions that want to control this device. A controller the user
        // already trusts is served without the app's own prompt, and if the screen is
        // already being captured it is attached with no system prompt either.
        ServiceLocator.sessions.observeIncoming(dto.id).collect { pending ->
            var toPrompt: SessionDto? = null
            for (session in pending) {
                if (session.id in answered) continue
                when {
                    ScreenCaptureService.capturing -> {
                        answered.add(session.id)
                        ScreenCaptureService.attachSession(app, session.id, session.controllerDevice)
                    }
                    TrustStore.isTrusted(app, session.controllerDevice) -> {
                        answered.add(session.id)
                        RequestCaptureActivity.request(app, session.id, session.controllerDevice)
                    }
                    toPrompt == null -> toPrompt = session
                }
            }
            _incoming.value = toPrompt
        }
    }

    /**
     * Re-register this device (idempotent on its device_code) and refresh the self id.
     * Heals a stale/deleted self id — e.g. after the row was removed, or a sign out/in —
     * so a session insert can't fail the devices foreign key. Returns the current id.
     */
    suspend fun ensureRegistered(context: Context): String? {
        val uid = startedForUid ?: return _selfDeviceId.value
        val app = context.applicationContext
        return try {
            registerSelf(app, uid).id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "ensureRegistered failed", e)
            _selfDeviceId.value
        }
    }

    private suspend fun registerSelf(app: Context, uid: String): DeviceDto {
        val dto = ServiceLocator.devices.registerSelf(
            NewDevice(
                ownerId = uid,
                deviceCode = LocalDeviceInfo.deviceCode(app),
                name = LocalDeviceInfo.defaultName,
                model = LocalDeviceInfo.model,
                manufacturer = LocalDeviceInfo.manufacturer,
                osVersion = LocalDeviceInfo.osVersion,
                appVersion = LocalDeviceInfo.appVersion,
                unattended = UnattendedAccess.isEnabled(app),
            ),
            previousCode = LocalDeviceInfo.storedCode(app),
        )
        _selfDeviceId.value = dto.id
        ScreenCaptureService.selfDeviceId = dto.id
        return dto
    }

    private fun reportPresence(online: Boolean) {
        val id = _selfDeviceId.value ?: return
        // While the capture service runs (unattended sharing), the device must stay
        // reachable even when the UI is backgrounded, so don't mark it offline then.
        if (!online && ScreenCaptureService.capturing) return
        scope.launch { runCatching { ServiceLocator.devices.setOnline(id, online) } }
    }

    /** The user allowed [session] and chose to remember this controller device. */
    fun approveAlways(context: Context, session: SessionDto) {
        TrustStore.trust(context, session.controllerDevice)
        approve(context, session)
    }

    /** The user allowed [session]; the system capture prompt comes next. */
    fun approve(context: Context, session: SessionDto) {
        answered.add(session.id)
        _incoming.value = null
        RequestCaptureActivity.request(context, session.id, session.controllerDevice)
    }

    /** The user refused, at our dialog or the system prompt. Tells the controller. */
    fun decline(sessionId: String, controllerDevice: String?) {
        answered.add(sessionId)
        if (_incoming.value?.id == sessionId) _incoming.value = null
        val self = _selfDeviceId.value
        scope.launch {
            runCatching {
                ServiceLocator.sessions.markAbandoned(sessionId)
                if (self != null && controllerDevice != null) {
                    ServiceLocator.signaling.send(sessionId, self, controllerDevice, "bye", "")
                }
            }
        }
    }

    fun onSignOut() {
        LiveWall.stopAll()
        job?.cancel()
        job = null
        startedForUid = null
        answered.clear()
        _selfDeviceId.value = null
        _incoming.value = null
        ScreenCaptureService.selfDeviceId = null
        ServiceLocator.clear()
    }
}
