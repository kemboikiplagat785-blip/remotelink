package com.acme.remotesupport.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.acme.remotesupport.data.webrtc.ControlEvent

/**
 * Injects remote [ControlEvent]s as real gestures/actions on this device.
 *
 * Consent model: Android forces the user to explicitly enable this service in
 * Settings, and the app only forwards events while an approved session is live.
 * Coordinates are normalized 0f..1f and scaled to the real display here.
 */
class ControlAccessibilityService : AccessibilityService() {

    // In-progress drag stroke (for drag_start/move/end continuity). A continuation
    // must start exactly where the previous stroke ended, so its end point is kept too.
    private var dragStroke: GestureDescription.StrokeDescription? = null
    private var dragX = 0f
    private var dragY = 0f

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /**
     * Read per event so rotation is honoured. Uses the full display, as captured by
     * MediaProjection, not resources.displayMetrics which can exclude the system bars.
     * Out-of-range input is clamped: negative gesture coordinates throw.
     */
    private fun px(xNorm: Float, yNorm: Float): Pair<Float, Float> {
        val size = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(size)
        return (xNorm.coerceIn(0f, 1f) * size.widthPixels) to (yNorm.coerceIn(0f, 1f) * size.heightPixels)
    }

    /** Entry point called by ScreenCaptureService for each inbound event. */
    fun dispatch(event: ControlEvent) {
        when (event) {
            is ControlEvent.Tap -> tap(event.x, event.y, 50L)
            is ControlEvent.DoubleTap -> doubleTap(event.x, event.y)
            is ControlEvent.LongPress -> tap(event.x, event.y, 600L)
            is ControlEvent.Swipe -> swipe(event.x1, event.y1, event.x2, event.y2, event.durationMs)
            is ControlEvent.DragStart -> dragStart(event.x, event.y)
            is ControlEvent.DragMove -> dragMove(event.x, event.y)
            is ControlEvent.DragEnd -> dragEnd(event.x, event.y)
            is ControlEvent.Scroll -> swipe(event.x, event.y, event.x + event.dx, event.y + event.dy, 180L)
            is ControlEvent.Nav -> nav(event.key)
            is ControlEvent.Text -> typeText(event.value)
            is ControlEvent.Key -> typeKey(event.value)
            is ControlEvent.SetQuality -> Unit // handled by the capture service
            is ControlEvent.Ping -> Unit
            is ControlEvent.Caps -> Unit // sent by this device, never received
            is ControlEvent.Auth -> Unit // handled by the capture service before dispatch
            is ControlEvent.AuthResult -> Unit // target→controller only, never received here
            ControlEvent.Bye -> Unit
        }
    }

    private fun tap(xNorm: Float, yNorm: Float, durationMs: Long) {
        val (x, y) = px(xNorm, yNorm)
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build(),
            null, null,
        )
    }

    // One gesture with two strokes: a second dispatchGesture would cancel the first.
    private fun doubleTap(xNorm: Float, yNorm: Float) {
        val (x, y) = px(xNorm, yNorm)
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 50L))
                .addStroke(GestureDescription.StrokeDescription(path, 120L, 50L))
                .build(),
            null, null,
        )
    }

    private fun swipe(x1n: Float, y1n: Float, x2n: Float, y2n: Float, durationMs: Long) {
        val (x1, y1) = px(x1n, y1n)
        val (x2, y2) = px(x2n, y2n)
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(20L, 60_000L)))
                .build(),
            null, null,
        )
    }

    private fun dragStart(xNorm: Float, yNorm: Float) {
        val (x, y) = px(xNorm, yNorm)
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 1L, true)
        dragStroke = stroke
        dragX = x
        dragY = y
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun dragMove(xNorm: Float, yNorm: Float) {
        val prev = dragStroke ?: return
        val (x, y) = px(xNorm, yNorm)
        val path = Path().apply { moveTo(dragX, dragY); lineTo(x, y) }
        val next = prev.continueStroke(path, 0L, 16L, true)
        dragStroke = next
        dragX = x
        dragY = y
        dispatchGesture(GestureDescription.Builder().addStroke(next).build(), null, null)
    }

    private fun dragEnd(xNorm: Float, yNorm: Float) {
        val prev = dragStroke ?: return
        val (x, y) = px(xNorm, yNorm)
        val path = Path().apply { moveTo(dragX, dragY); lineTo(x, y) }
        val end = prev.continueStroke(path, 0L, 16L, false)
        dragStroke = null
        dispatchGesture(GestureDescription.Builder().addStroke(end).build(), null, null)
    }

    private fun nav(key: String) {
        val action = when (key.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recent", "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "lock" -> GLOBAL_ACTION_LOCK_SCREEN
            else -> return
        }
        performGlobalAction(action)
    }

    private fun focusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        findFocusInto(root)?.let { return it }
        return null
    }

    private fun findFocusInto(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findFocusInto(child)?.let { return it }
        }
        return null
    }

    private fun typeText(value: String) {
        val node = focusedEditable() ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        node ?: return
        val existing = node.text?.toString() ?: ""
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                existing + value,
            )
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun typeKey(value: String) {
        when (value.lowercase()) {
            "del", "backspace" -> {
                val node = focusedEditable() ?: return
                val text = node.text?.toString() ?: return
                if (text.isNotEmpty()) {
                    val args = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            text.dropLast(1),
                        )
                    }
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }
            }
            "enter" -> focusedEditable()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    companion object {
        @Volatile var instance: ControlAccessibilityService? = null
            private set

        val isEnabled: Boolean get() = instance != null
    }
}
