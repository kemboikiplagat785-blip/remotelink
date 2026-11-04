package com.acme.remotesupport.ui.control

import android.content.Context
import com.acme.remotesupport.data.ServiceLocator
import com.acme.remotesupport.data.local.ControllerPinStore
import com.acme.remotesupport.data.webrtc.ControlEvent
import com.acme.remotesupport.data.webrtc.PeerRole
import com.acme.remotesupport.data.webrtc.RtcPeer
import com.acme.remotesupport.service.ControlSessionService
import com.acme.remotesupport.service.ScreenCaptureService
import com.acme.remotesupport.ui.AppCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class LinkStatus { WAITING, CONNECTING, LIVE, DECLINED, NO_RESPONSE, ENDED, ERROR }

data class LiveTile(
    val status: LinkStatus = LinkStatus.WAITING,
    val track: VideoTrack? = null,
    /** Null until the target reports whether its accessibility service is on. */
    val canControl: Boolean? = null,
    val message: String? = null,
)

/**
 * CONTROLLER side. Holds one session per target so every approved phone stays live
 * at once. It outlives the screens: opening a phone for control reuses its running
 * connection, and each target's user approves its session as before.
 */
object LiveWall {
    private const val APPROVAL_TIMEOUT_MS = 90_000L
    private const val MAX_RECONNECTS = 5
    private const val RECONNECT_BACKOFF_MS = 1_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val links = ConcurrentHashMap<String, Link>()

    private val _tiles = MutableStateFlow<Map<String, LiveTile>>(emptyMap())
    val tiles: StateFlow<Map<String, LiveTile>> = _tiles

    @Volatile private var focused: String? = null

    /**
     * Asks [targetId] for a session, unless one is already running.
     *
     * [pin] is the unattended-access PIN for that target. When provided, it is sent as
     * soon as the data channel opens so a trusted target serves the session without a
     * live accept on the controlled phone. Omit it for the interactive consent flow.
     */
    fun connect(context: Context, targetId: String, pin: String? = null) {
        val self = AppCoordinator.selfDeviceId.value
        if (self == null) {
            edit(targetId) { LiveTile(LinkStatus.ERROR, message = "This phone is not registered yet.") }
            return
        }
        if (links.containsKey(targetId)) return
        val app = context.applicationContext
        val link = Link(targetId, self, app, pin ?: ControllerPinStore.get(app, targetId))
        links[targetId] = link
        edit(targetId) { LiveTile(LinkStatus.WAITING) }
        runCatching { ControlSessionService.start(app, "your devices") }
        link.start()
    }

    fun send(targetId: String, event: ControlEvent) {
        links[targetId]?.peer?.sendControl(event)
    }

    /** The opened phone streams at full quality; the rest stay at thumbnail quality. */
    fun focus(targetId: String?) {
        val previous = focused
        focused = targetId
        if (previous != null && previous != targetId) send(previous, ControlEvent.SetQuality(ControlEvent.QUALITY_LOW))
        if (targetId != null) send(targetId, ControlEvent.SetQuality(ControlEvent.QUALITY_HIGH))
    }

    fun stop(targetId: String, reason: String = "controller_ended") {
        links[targetId]?.hangUp(reason)
        _tiles.update { it - targetId }
    }

    fun stopAll() {
        links.values.toList().forEach { it.hangUp("controller_ended") }
        _tiles.value = emptyMap()
    }

    private fun edit(targetId: String, change: (LiveTile) -> LiveTile) {
        _tiles.update { it + (targetId to change(it[targetId] ?: LiveTile())) }
    }

    private class Link(val targetId: String, initialSelf: String, val app: Context, val pin: String?) {
        // Refreshed from a re-registration before the session starts, so a stale/deleted
        // self id can't violate the remote_sessions -> devices foreign key.
        @Volatile private var self: String = initialSelf
        val peer = RtcPeer.create(app, PeerRole.CONTROLLER)
        private val sessions = ServiceLocator.sessions
        private val signaling = ServiceLocator.signaling
        private val finished = AtomicBoolean(false)
        private var job: Job? = null
        @Volatile private var sessionId: String? = null
        @Volatile private var answered = false
        @Volatile private var wasLive = false
        private val reconnects = AtomicInteger(0)
        @Volatile private var reconnectJob: Job? = null

        fun start() {
            peer.setListener(object : RtcPeer.Listener {
                override fun onLocalIceCandidate(candidate: IceCandidate) {
                    signal("ice", ScreenCaptureService.encodeIce(candidate))
                }
                override fun onRemoteVideoTrack(track: VideoTrack) {
                    track.setEnabled(true)
                    edit(targetId) { it.copy(track = track) }
                }
                override fun onConnected() {
                    wasLive = true
                    reconnects.set(0)
                    reconnectJob?.cancel()
                    reconnectJob = null
                    edit(targetId) { it.copy(status = LinkStatus.LIVE, message = null) }
                }
                override fun onDisconnected() = scheduleReconnect()
                override fun onFailed() = scheduleReconnect()
                override fun onDataChannelOpen() {
                    // The PIN must arrive before anything else on an unattended target.
                    pin?.let { peer.sendControl(ControlEvent.Auth(it)) }
                    val level = if (focused == targetId) ControlEvent.QUALITY_HIGH else ControlEvent.QUALITY_LOW
                    peer.sendControl(ControlEvent.SetQuality(level))
                }
                override fun onControlEvent(event: ControlEvent) {
                    when (event) {
                        is ControlEvent.Caps -> edit(targetId) { it.copy(canControl = event.canControl) }
                        is ControlEvent.AuthResult ->
                            if (event.ok) {
                                // Correct PIN: remember it so later connects are promptless.
                                pin?.let { ControllerPinStore.put(app, targetId, it) }
                            } else {
                                // Forget a wrong/rejected PIN so the next attempt prompts again.
                                ControllerPinStore.remove(app, targetId)
                                finish(LinkStatus.ERROR, "PIN not accepted. Tap to re-enter.")
                            }
                        ControlEvent.Bye -> finish(LinkStatus.ENDED)
                        else -> Unit
                    }
                }
                override fun onClosed() {
                    if (wasLive) finish(LinkStatus.ENDED) else finish(LinkStatus.ERROR, "Connection failed.")
                }
            })
            peer.start()

            job = scope.launch {
                try {
                    // Make sure this phone is actually registered before inserting the session,
                    // so a stale id (after a removed device or a sign out/in) is healed first.
                    AppCoordinator.ensureRegistered(app)?.let { self = it }
                    val session = sessions.startSession(self, targetId, "control")
                    sessionId = session.id
                    launch {
                        signaling.incoming(session.id, self)
                            .catch { e -> finish(LinkStatus.ERROR, e.message) }
                            .collect { signal ->
                                when (signal.kind) {
                                    "answer" -> {
                                        answered = true
                                        edit(targetId) { it.copy(status = LinkStatus.CONNECTING) }
                                        peer.setRemoteDescription(
                                            SessionDescription(SessionDescription.Type.ANSWER, signal.payload),
                                        )
                                    }
                                    "ice" -> ScreenCaptureService.decodeIce(signal.payload)?.let { peer.addIceCandidate(it) }
                                    // The target declined or stopped sharing, and has recorded why.
                                    "bye" -> finish(if (wasLive) LinkStatus.ENDED else LinkStatus.DECLINED)
                                }
                            }
                    }
                    peer.createOffer { offer ->
                        peer.setLocalDescription(offer)
                        signal("offer", offer.description)
                    }
                    delay(APPROVAL_TIMEOUT_MS)
                    if (!answered) {
                        // Tell the target too, so a late approval doesn't start capturing for nobody.
                        scope.launch {
                            runCatching {
                                sessions.markAbandoned(session.id)
                                signaling.send(session.id, self, targetId, "bye", "")
                            }
                        }
                        finish(LinkStatus.NO_RESPONSE)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    finish(LinkStatus.ERROR, e.message)
                }
            }
        }

        /**
         * The connection dropped. Re-offer with an ICE restart (backing off between tries)
         * rather than ending the session, so a network blip is invisible to the user.
         */
        private fun scheduleReconnect() {
            if (finished.get()) return
            if (reconnectJob?.isActive == true) return
            val attempt = reconnects.incrementAndGet()
            if (attempt > MAX_RECONNECTS) {
                finish(if (wasLive) LinkStatus.ENDED else LinkStatus.ERROR, "Connection lost.")
                return
            }
            edit(targetId) { it.copy(status = LinkStatus.CONNECTING, message = "Reconnecting…") }
            reconnectJob = scope.launch {
                delay(RECONNECT_BACKOFF_MS * attempt)
                if (finished.get()) return@launch
                peer.restartIce { offer ->
                    peer.setLocalDescription(offer)
                    signal("offer", offer.description)
                }
            }
        }

        /** This side is ending the session, so the target has to be told. */
        fun hangUp(reason: String) {
            if (!finished.compareAndSet(false, true)) return
            peer.sendControl(ControlEvent.Bye)
            val sid = sessionId
            if (sid != null) {
                scope.launch {
                    runCatching {
                        sessions.endSession(sid, reason)
                        signaling.send(sid, self, targetId, "bye", "")
                    }
                }
            }
            release()
        }

        /** The session ended for a reason this side only has to report. */
        private fun finish(status: LinkStatus, message: String? = null) {
            if (!finished.compareAndSet(false, true)) return
            edit(targetId) { LiveTile(status, message = message) }
            release()
        }

        private fun release() {
            job?.cancel()
            reconnectJob?.cancel()
            links.remove(targetId, this)
            if (links.isEmpty()) ControlSessionService.stop(app)
            // Callers may be on WebRTC's signaling thread, where closing the connection deadlocks.
            scope.launch { peer.close() }
        }

        private fun signal(kind: String, payload: String) {
            val sid = sessionId ?: return
            scope.launch { runCatching { signaling.send(sid, self, targetId, kind, payload) } }
        }
    }
}
