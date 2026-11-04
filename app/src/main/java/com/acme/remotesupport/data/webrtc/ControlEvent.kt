package com.acme.remotesupport.data.webrtc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The remote-control wire protocol. Controller serializes these to JSON and sends
 * them over the WebRTC data channel; the target deserializes and injects them via
 * [com.acme.remotesupport.accessibility.ControlAccessibilityService].
 *
 * Coordinates are normalized 0f..1f relative to the target's screen so they are
 * resolution-independent; the target multiplies by its real width/height.
 */
@Serializable
sealed class ControlEvent {
    @Serializable @SerialName("tap")
    data class Tap(val x: Float, val y: Float) : ControlEvent()

    @Serializable @SerialName("double_tap")
    data class DoubleTap(val x: Float, val y: Float) : ControlEvent()

    @Serializable @SerialName("long_press")
    data class LongPress(val x: Float, val y: Float) : ControlEvent()

    @Serializable @SerialName("swipe")
    data class Swipe(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val durationMs: Long = 250L,
    ) : ControlEvent()

    @Serializable @SerialName("drag_start")
    data class DragStart(val x: Float, val y: Float) : ControlEvent()

    @Serializable @SerialName("drag_move")
    data class DragMove(val x: Float, val y: Float) : ControlEvent()

    @Serializable @SerialName("drag_end")
    data class DragEnd(val x: Float, val y: Float) : ControlEvent()

    @Serializable @SerialName("scroll")
    data class Scroll(val x: Float, val y: Float, val dx: Float, val dy: Float) : ControlEvent()

    /** Global navigation: "back" | "home" | "recent". */
    @Serializable @SerialName("nav")
    data class Nav(val key: String) : ControlEvent()

    /** Type text into the currently focused editable field. */
    @Serializable @SerialName("text")
    data class Text(val value: String) : ControlEvent()

    /** A single key action, e.g. "enter" | "del" | "tab". */
    @Serializable @SerialName("key")
    data class Key(val value: String) : ControlEvent()

    /** Ask the target to change its encoder quality. */
    @Serializable @SerialName("set_quality")
    data class SetQuality(val level: String) : ControlEvent()

    @Serializable @SerialName("ping")
    data class Ping(val t: Long = System.currentTimeMillis()) : ControlEvent()

    /**
     * Controller to target: the unattended-access PIN, sent as the first message once
     * the data channel opens. The target verifies it against its locally stored salted
     * hash before serving any control. Travels only over the encrypted DTLS data channel.
     */
    @Serializable @SerialName("auth")
    data class Auth(val pin: String) : ControlEvent()

    /** Target to controller: whether the PIN was accepted. */
    @Serializable @SerialName("auth_result")
    data class AuthResult(val ok: Boolean, val reason: String? = null) : ControlEvent()

    /** Target to controller: whether inbound events can be injected right now. */
    @Serializable @SerialName("caps")
    data class Caps(val canControl: Boolean) : ControlEvent()

    /** End of control session. */
    @Serializable @SerialName("bye")
    data object Bye : ControlEvent()

    companion object {
        const val QUALITY_LOW = "low"
        const val QUALITY_HIGH = "high"

        val json: Json = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
            encodeDefaults = true
        }

        fun encode(event: ControlEvent): String = json.encodeToString(event)
        fun decode(text: String): ControlEvent = json.decodeFromString(text)
    }
}
