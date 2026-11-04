package com.acme.remotesupport.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.acme.remotesupport.R
import com.acme.remotesupport.RemoteApp
import com.acme.remotesupport.accessibility.ControlAccessibilityService
import com.acme.remotesupport.data.ServiceLocator
import com.acme.remotesupport.data.local.UnattendedAccess
import com.acme.remotesupport.data.webrtc.ControlEvent
import com.acme.remotesupport.data.webrtc.PeerRole
import com.acme.remotesupport.data.webrtc.RtcPeer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TARGET side. Holds the user-approved MediaProjection and keeps capturing the
 * screen while remote support is on, so a controller the user already trusts can
 * reconnect without a new system prompt. One capture feeds many controller
 * sessions (each its own peer connection); inbound control events reach the
 * accessibility service.
 */
class ScreenCaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var capturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var factory: PeerConnectionFactory? = null
    private val sessions = ConcurrentHashMap<String, SessionPeer>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_ATTACH -> {
                // Reconnect while capture is already live: no new system prompt.
                startForeground()
                val sid = intent.getStringExtra(EXTRA_SESSION_ID)
                val controller = intent.getStringExtra(EXTRA_CONTROLLER_DEVICE_ID)
                if (capturing && sid != null) addSession(sid, controller)
                return START_NOT_STICKY
            }
            else -> {
                startForeground()
                val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
                val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val sid = intent?.getStringExtra(EXTRA_SESSION_ID)
                val controller = intent?.getStringExtra(EXTRA_CONTROLLER_DEVICE_ID)
                if (resultCode != 0 && data != null) {
                    if (!capturing) beginCapture(resultCode, data)
                    if (sid != null) addSession(sid, controller)
                }
                return START_NOT_STICKY
            }
        }
    }

    private fun startForeground() {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = NotificationCompat.Builder(this, RemoteApp.CH_CAPTURE)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(notificationText())
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(0, getString(R.string.capture_stop_action), stop)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun notificationText(): String =
        if (sessions.isEmpty()) {
            "Remote support is on. Your screen can be viewed by your devices. Tap Stop to turn off."
        } else {
            "Your screen is being shared in a support session."
        }

    private fun updateNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForeground()
    }

    private fun beginCapture(resultCode: Int, data: Intent) {
        val f = RtcPeer.sharedFactory(this)
        factory = f
        surfaceHelper = SurfaceTextureHelper.create("CaptureThread", RtcPeer.sharedEglContext)
        videoSource = f.createVideoSource(/* isScreencast = */ true)

        // The full display: resources.displayMetrics can exclude the system bars.
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val callback = object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() } // user revoked sharing in the system UI
        }
        capturer = ScreenCapturerAndroid(data, callback).also { cap ->
            cap.initialize(surfaceHelper, applicationContext, videoSource!!.capturerObserver)
            cap.startCapture(metrics.widthPixels, metrics.heightPixels, 30)
        }
        capturing = true
    }

    private fun addSession(sessionId: String, controllerDeviceId: String?) {
        val selfId = selfDeviceId ?: return
        val source = videoSource ?: return
        val f = factory ?: return
        if (controllerDeviceId == null) return
        if (sessions.containsKey(sessionId)) return
        val sp = SessionPeer(sessionId, controllerDeviceId, selfId)
        sessions[sessionId] = sp
        sp.start(source, f)
        updateNotification()
    }

    /** Called by a [SessionPeer] once it has fully torn down. */
    private fun onSessionClosed(sessionId: String, peer: SessionPeer) {
        sessions.remove(sessionId, peer)
        // The capture deliberately stays alive for an instant, promptless reconnect.
        updateNotification()
    }

    override fun onDestroy() {
        capturing = false
        sessions.values.toList().forEach { it.close() }
        sessions.clear()
        runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        capturer = null
        videoSource?.dispose()
        videoSource = null
        surfaceHelper?.dispose()
        surfaceHelper = null
        // factory + EGL are shared with the controller side; never disposed here.
        scope.cancel()
        super.onDestroy()
    }

    /** One controller's session: its own peer connection and video track off the shared capture. */
    private inner class SessionPeer(
        val sessionId: String,
        val controllerId: String,
        val selfId: String,
    ) {
        private val peer = RtcPeer.create(this@ScreenCaptureService, PeerRole.TARGET)
        private var track: VideoTrack? = null
        private var signalingJob: Job? = null
        private var capsJob: Job? = null
        private var adaptJob: Job? = null
        private var giveUpJob: Job? = null
        private var authJob: Job? = null
        private val closed = AtomicBoolean(false)
        private val endedByController = AtomicBoolean(false)

        // When unattended access is enabled, the controller must present the PIN before
        // any control is injected. Otherwise (legacy consent flow) control is open from
        // the start, because the user approved the session explicitly.
        private val pinRequired = UnattendedAccess.isEnabled(this@ScreenCaptureService)
        private val authed = AtomicBoolean(!pinRequired)

        fun start(source: VideoSource, f: PeerConnectionFactory) {
            peer.setListener(object : RtcPeer.Listener {
                override fun onLocalIceCandidate(candidate: IceCandidate) {
                    sendSignal(sessionId, selfId, controllerId, "ice", encodeIce(candidate))
                }
                override fun onControlEvent(event: ControlEvent) {
                    when (event) {
                        is ControlEvent.Auth -> handleAuth(event.pin)
                        ControlEvent.Bye -> { endedByController.set(true); close() }
                        is ControlEvent.SetQuality -> peer.setVideoQuality(event.level)
                        // Everything else drives the device: refuse until the PIN is verified.
                        else -> if (authed.get()) ControlAccessibilityService.instance?.dispatch(event)
                    }
                }
                override fun onConnected() {
                    // Recovered (first connect or after an ICE restart): stand the watchdog down.
                    giveUpJob?.cancel()
                    giveUpJob = null
                }
                override fun onDisconnected() = armGiveUp()
                override fun onFailed() = armGiveUp()
                override fun onDataChannelOpen() {
                    // Give the controller a brief window to present the PIN; if it never
                    // arrives (or is wrong) the session is dropped, so an unauthenticated
                    // peer can neither control nor keep watching.
                    if (pinRequired && !authed.get()) armAuthTimeout()
                    capsJob?.cancel()
                    capsJob = scope.launch { reportControlAccess(peer) }
                }
                override fun onClosed() { close() }
            })
            peer.start()
            track = f.createVideoTrack("screen-$sessionId", source).also { peer.addVideoTrack(it) }
            adaptJob = scope.launch { peer.runAdaptiveBitrate() }

            signalingJob = scope.launch {
                runCatching { ServiceLocator.sessions.markActive(sessionId) }
                ServiceLocator.signaling.incoming(sessionId, selfId)
                    .catch { close() }
                    .collect { signal ->
                        when (signal.kind) {
                            "offer" -> peer.setRemoteDescription(
                                SessionDescription(SessionDescription.Type.OFFER, signal.payload),
                            ) {
                                peer.createAnswer { answer ->
                                    peer.setLocalDescription(answer)
                                    sendSignal(sessionId, selfId, controllerId, "answer", answer.description)
                                }
                            }
                            "ice" -> decodeIce(signal.payload)?.let { peer.addIceCandidate(it) }
                            "bye" -> { endedByController.set(true); close() }
                        }
                    }
            }
        }

        /** Verify the PIN the controller presented; accept or tear the session down. */
        private fun handleAuth(pin: String) {
            if (authed.get()) { peer.sendControl(ControlEvent.AuthResult(true)); return }
            val ok = UnattendedAccess.verify(this@ScreenCaptureService, pin)
            if (ok) {
                authed.set(true)
                authJob?.cancel()
                authJob = null
                peer.sendControl(ControlEvent.AuthResult(true))
            } else {
                peer.sendControl(ControlEvent.AuthResult(false, "pin"))
                endedByController.set(true) // controller already knows why; don't send a second bye
                close()
            }
        }

        private fun armAuthTimeout() {
            if (authJob?.isActive == true) return
            authJob = scope.launch {
                delay(AUTH_TIMEOUT_MS)
                if (!authed.get() && !closed.get()) {
                    peer.sendControl(ControlEvent.AuthResult(false, "timeout"))
                    close()
                }
            }
        }

        /**
         * The controller dropped. Wait out the grace window for its ICE restart to land
         * ([onConnected] cancels this); if nothing reconnects, release the session.
         */
        private fun armGiveUp() {
            if (closed.get() || giveUpJob?.isActive == true) return
            giveUpJob = scope.launch {
                delay(RECONNECT_GIVEUP_MS)
                if (!closed.get()) close()
            }
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            signalingJob?.cancel()
            capsJob?.cancel()
            adaptJob?.cancel()
            giveUpJob?.cancel()
            authJob?.cancel()
            if (!endedByController.get()) {
                peer.sendControl(ControlEvent.Bye)
                // Outlives this service's scope, which is cancelled on destroy.
                cleanupScope.launch {
                    runCatching {
                        ServiceLocator.sessions.endSession(sessionId, "target_ended")
                        ServiceLocator.signaling.send(sessionId, selfId, controllerId, "bye", "")
                    }
                }
            }
            peer.setListener(null)
            peer.close()
            track?.dispose()
            track = null
            onSessionClosed(sessionId, this)
        }
    }

    /** The user can switch the accessibility service on or off during a session. */
    private suspend fun reportControlAccess(peer: RtcPeer) {
        var last: Boolean? = null
        while (currentCoroutineContext().isActive) {
            val canControl = ControlAccessibilityService.instance != null
            if (canControl != last) {
                peer.sendControl(ControlEvent.Caps(canControl))
                last = canControl
            }
            delay(CAPS_POLL_MS)
        }
    }

    private fun sendSignal(sessionId: String, from: String, to: String, kind: String, payload: String) {
        scope.launch { runCatching { ServiceLocator.signaling.send(sessionId, from, to, kind, payload) } }
    }

    companion object {
        private const val NOTIF_ID = 42
        private const val CAPS_POLL_MS = 2_000L

        /** How long to keep a session's capture alive waiting for the controller to reconnect. */
        private const val RECONNECT_GIVEUP_MS = 20_000L

        /** How long an unattended session may stay unauthenticated before it is dropped. */
        private const val AUTH_TIMEOUT_MS = 10_000L
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_CONTROLLER_DEVICE_ID = "controller_device_id"
        private const val ACTION_STOP = "com.acme.remotesupport.action.STOP_SHARING"
        private const val ACTION_ATTACH = "com.acme.remotesupport.action.ATTACH_SESSION"

        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** True while the screen is being captured, so a reconnect can skip the system prompt. */
        @Volatile var capturing = false
            private set

        /** Set by the app once this device is registered, so the service knows its own id. */
        @Volatile var selfDeviceId: String? = null

        fun startWithProjection(
            context: Context,
            resultCode: Int,
            data: Intent,
            sessionId: String?,
            controllerDeviceId: String?,
        ) {
            val i = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_CONTROLLER_DEVICE_ID, controllerDeviceId)
            }
            context.startForegroundService(i)
        }

        /** Serve another controller session on the capture that is already running. */
        fun attachSession(context: Context, sessionId: String, controllerDeviceId: String?) {
            val i = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_ATTACH
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_CONTROLLER_DEVICE_ID, controllerDeviceId)
            }
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }

        fun encodeIce(c: IceCandidate): String =
            ControlEvent.json.encodeToString(
                IceWire.serializer(), IceWire(c.sdpMid, c.sdpMLineIndex, c.sdp),
            )

        fun decodeIce(payload: String): IceCandidate? = runCatching {
            val w = ControlEvent.json.decodeFromString(IceWire.serializer(), payload)
            IceCandidate(w.sdpMid, w.sdpMLineIndex, w.sdp)
        }.getOrNull()
    }

    @kotlinx.serialization.Serializable
    data class IceWire(val sdpMid: String?, val sdpMLineIndex: Int, val sdp: String)
}
