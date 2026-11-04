package com.acme.remotesupport.data.webrtc

import android.content.Context
import com.acme.remotesupport.BuildConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpSender
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume

/** Roles in a session. */
enum class PeerRole { CONTROLLER, TARGET }

/**
 * Thin wrapper over a single WebRTC [PeerConnection]. The target adds a screen
 * video track (and optional audio) and receives [ControlEvent]s over a data
 * channel; the controller receives the video track and sends control events.
 */
class RtcPeer private constructor(
    private val factory: PeerConnectionFactory,
    val eglBase: EglBase,
    private val role: PeerRole,
) {
    interface Listener {
        fun onLocalIceCandidate(candidate: IceCandidate)
        fun onControlEvent(event: ControlEvent) {}
        fun onRemoteVideoTrack(track: VideoTrack) {}
        fun onRemoteAudioTrack(track: AudioTrack) {}
        fun onConnected() {}
        /** Transient drop; the connection may recover on its own or via an ICE restart. */
        fun onDisconnected() {}
        /** ICE has given up. Recoverable only by an ICE restart (see [restartIce]). */
        fun onFailed() {}
        /** The connection is gone for good (explicit [close]). */
        fun onClosed() {}
        fun onDataChannelOpen() {}
    }

    private var listener: Listener? = null
    private var connection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var videoSender: RtpSender? = null

    // Candidates can arrive before the remote description is applied; WebRTC rejects those.
    private val pendingIce = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false

    fun setListener(l: Listener?) { listener = l }

    private fun iceServers(): List<PeerConnection.IceServer> = buildList {
        BuildConfig.STUN_URL.takeIf { it.isNotBlank() }?.let {
            add(PeerConnection.IceServer.builder(it).createIceServer())
        }
        // TURN_URL may hold several comma-separated relay URLs (e.g. Metered gives you
        // :80, :80?transport=tcp, :443 and turns:443) that all share one credential.
        val turnUrls = BuildConfig.TURN_URL
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.contains("YOUR-TURN") }
        if (turnUrls.isNotEmpty()) {
            add(
                PeerConnection.IceServer.builder(turnUrls)
                    .setUsername(BuildConfig.TURN_USERNAME)
                    .setPassword(BuildConfig.TURN_PASSWORD)
                    .createIceServer(),
            )
        }
    }

    fun start() {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        connection = factory.createPeerConnection(rtcConfig, observer) ?: error("no PeerConnection")

        // Controller creates the control data channel; target receives it in onDataChannel.
        if (role == PeerRole.CONTROLLER) {
            val init = DataChannel.Init().apply { ordered = true }
            dataChannel = connection!!.createDataChannel("control", init)
            dataChannel?.registerObserver(dataObserver(dataChannel!!))
        }
    }

    /** Target attaches its screen capture track (and optional audio). */
    fun addVideoTrack(track: VideoTrack) {
        videoSender = connection?.addTrack(track, listOf("screen"))
    }

    // Adaptive bitrate (sender/target side). The focus level sets a ceiling; the
    // adaptation loop lowers the actual bitrate under congestion and recovers slowly.
    @Volatile private var ceilingHigh = false
    @Volatile private var currentBitrate = LOW_BITRATE

    /** Target: the wall needs thumbnails from many phones, the opened phone needs full quality. */
    fun setVideoQuality(level: String) {
        ceilingHigh = level == ControlEvent.QUALITY_HIGH
        // Snap toward the new ceiling so focusing a phone feels immediate; the loop fine-tunes.
        currentBitrate = if (ceilingHigh) HIGH_BITRATE else LOW_BITRATE
        applyEncoding()
    }

    private fun applyEncoding() {
        val sender = videoSender ?: return
        val parameters = sender.parameters
        parameters.encodings.forEach {
            it.scaleResolutionDownBy = if (ceilingHigh) 1.0 else 3.0
            it.maxFramerate = if (ceilingHigh) 30 else 8
            it.maxBitrateBps = currentBitrate
        }
        runCatching { sender.setParameters(parameters) }
    }

    /**
     * Target: an AIMD loop that trims the send bitrate when the link shows packet loss
     * or the bandwidth estimate drops, and raises it gently back toward the ceiling when
     * the link is clean. Call from a coroutine for the session's lifetime.
     */
    suspend fun runAdaptiveBitrate() {
        while (currentCoroutineContext().isActive) {
            delay(ADAPT_INTERVAL_MS)
            if (videoSender == null) continue
            val stats = awaitStats() ?: continue
            val ceiling = if (ceilingHigh) HIGH_BITRATE else LOW_BITRATE
            val floor = if (ceilingHigh) HIGH_FLOOR else LOW_FLOOR
            currentBitrate = when {
                stats.fractionLost > 0.10 ||
                    (stats.availableOutgoingBitrate > 0 && stats.availableOutgoingBitrate < currentBitrate * 0.8) ->
                    (currentBitrate * 0.7).toInt().coerceAtLeast(floor)
                stats.fractionLost < 0.02 ->
                    (currentBitrate + BITRATE_STEP).coerceAtMost(ceiling)
                else -> currentBitrate
            }.coerceIn(floor, ceiling)
            applyEncoding()
        }
    }

    private class Stats(val fractionLost: Double, val availableOutgoingBitrate: Double)

    private suspend fun awaitStats(): Stats? = suspendCancellableCoroutine { cont ->
        val conn = connection
        if (conn == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        conn.getStats { report ->
            var loss = 0.0
            var avail = 0.0
            for (s in report.statsMap.values) {
                when (s.type) {
                    "remote-inbound-rtp" ->
                        (s.members["fractionLost"] as? Double)?.let { loss = maxOf(loss, it) }
                    "candidate-pair" -> {
                        val active = (s.members["nominated"] as? Boolean) == true ||
                            (s.members["state"] as? String) == "succeeded"
                        if (active) (s.members["availableOutgoingBitrate"] as? Double)?.let { avail = it }
                    }
                }
            }
            if (cont.isActive) cont.resume(Stats(loss, avail))
        }
    }

    /** Controller: re-offer with a fresh ICE restart to recover a dropped/failed connection. */
    fun restartIce(onSdp: (SessionDescription) -> Unit) {
        val constraints = mediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        connection?.createOffer(sdpCreateObserver(onSdp), constraints)
    }

    fun addAudioTrack(track: AudioTrack) {
        connection?.addTrack(track, listOf("screen"))
    }

    fun sendControl(event: ControlEvent) {
        val dc = dataChannel ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        val bytes = ControlEvent.encode(event).toByteArray(StandardCharsets.UTF_8)
        dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    // -- SDP --------------------------------------------------------------
    fun createOffer(onSdp: (SessionDescription) -> Unit) {
        connection?.createOffer(sdpCreateObserver(onSdp), mediaConstraints())
    }
    fun createAnswer(onSdp: (SessionDescription) -> Unit) {
        connection?.createAnswer(sdpCreateObserver(onSdp), mediaConstraints())
    }
    fun setLocalDescription(sdp: SessionDescription) {
        connection?.setLocalDescription(sdpSetObserver(), sdp)
    }
    fun setRemoteDescription(sdp: SessionDescription, onSet: () -> Unit = {}) {
        connection?.setRemoteDescription(
            sdpSetObserver {
                val queued = synchronized(pendingIce) {
                    remoteDescriptionSet = true
                    pendingIce.toList().also { pendingIce.clear() }
                }
                queued.forEach { connection?.addIceCandidate(it) }
                onSet()
            },
            sdp,
        )
    }
    fun addIceCandidate(candidate: IceCandidate) {
        val ready = synchronized(pendingIce) {
            if (!remoteDescriptionSet) pendingIce.add(candidate)
            remoteDescriptionSet
        }
        if (ready) connection?.addIceCandidate(candidate)
    }

    fun close() {
        runCatching { dataChannel?.close() }
        runCatching { connection?.close() }
        dataChannel = null
        connection = null
        listener?.onClosed()
    }

    private fun mediaConstraints() = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (role == PeerRole.CONTROLLER) "true" else "false"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
    }

    private val observer = object : PeerConnectionObserverAdapter() {
        override fun onIceCandidate(candidate: IceCandidate) {
            listener?.onLocalIceCandidate(candidate)
        }
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> listener?.onConnected()
                // A transient drop: recoverable, so reconnection can be attempted rather than torn down.
                PeerConnection.PeerConnectionState.DISCONNECTED -> listener?.onDisconnected()
                // ICE has failed; an ICE restart can still bring it back.
                PeerConnection.PeerConnectionState.FAILED -> listener?.onFailed()
                PeerConnection.PeerConnectionState.CLOSED -> listener?.onClosed()
                else -> Unit
            }
        }
        override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
            when (val t = transceiver.receiver.track()) {
                is VideoTrack -> listener?.onRemoteVideoTrack(t)
                is AudioTrack -> listener?.onRemoteAudioTrack(t)
                else -> Unit
            }
        }
        override fun onDataChannel(dc: DataChannel) {
            dataChannel = dc
            dc.registerObserver(dataObserver(dc))
            // An inbound channel can already be open, in which case no state change follows.
            if (dc.state() == DataChannel.State.OPEN) listener?.onDataChannelOpen()
        }
    }

    private fun dataObserver(dc: DataChannel) = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) {}
        override fun onStateChange() {
            if (dc.state() == DataChannel.State.OPEN) listener?.onDataChannelOpen()
        }
        override fun onMessage(buffer: DataChannel.Buffer) {
            val data = ByteArray(buffer.data.remaining())
            buffer.data.get(data)
            val text = String(data, StandardCharsets.UTF_8)
            runCatching { ControlEvent.decode(text) }.getOrNull()?.let {
                listener?.onControlEvent(it)
            }
        }
    }

    private fun sdpCreateObserver(onSdp: (SessionDescription) -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = onSdp(sdp)
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
    private fun sdpSetObserver(onSet: () -> Unit = {}) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() = onSet()
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    companion object {
        // Adaptive-bitrate bounds (bits/sec) and cadence.
        private const val HIGH_BITRATE = 2_500_000
        private const val LOW_BITRATE = 300_000
        private const val HIGH_FLOOR = 500_000
        private const val LOW_FLOOR = 120_000
        private const val BITRATE_STEP = 250_000
        private const val ADAPT_INTERVAL_MS = 2_000L

        // Shared by every peer: the wall runs many at once, and all render through one EGL context.
        @Volatile private var sharedEgl: EglBase? = null
        private var sharedFactory: PeerConnectionFactory? = null

        /** Available once a peer has been created, which any video track implies. */
        val sharedEglContext: EglBase.Context? get() = sharedEgl?.eglBaseContext

        @Synchronized
        fun create(context: Context, role: PeerRole): RtcPeer {
            val factory = sharedFactory ?: run {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .createInitializationOptions(),
                )
                val egl = EglBase.create()
                sharedEgl = egl
                PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                    .createPeerConnectionFactory()
                    .also { sharedFactory = it }
            }
            return RtcPeer(factory, sharedEgl!!, role)
        }

        /** The shared factory, initializing it if needed, for a capture source with no single peer. */
        fun sharedFactory(context: Context): PeerConnectionFactory {
            create(context, PeerRole.TARGET) // ensures the factory + EGL exist; the wrapper is unused
            return sharedFactory!!
        }
    }

    /** Exposed so the target service can build its screen video source/track. */
    val peerConnectionFactory: PeerConnectionFactory get() = factory
}
