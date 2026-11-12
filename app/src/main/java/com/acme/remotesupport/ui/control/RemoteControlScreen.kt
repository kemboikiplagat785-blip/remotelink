package com.acme.remotesupport.ui.control

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.acme.remotesupport.data.webrtc.ControlEvent
import kotlin.math.roundToInt

/**
 * Full-screen control of one phone. This phone's own system bars are hidden and the
 * remote display fills the screen, including the controlled phone's status bar and
 * navigation keys, which work when tapped. The menu stays hidden until asked for, from
 * a small handle that can be dragged off whatever it covers, or with this phone's Back.
 */
@Composable
fun RemoteControlScreen(
    targetDeviceId: String,
    onBack: () -> Unit,
) {
    val tiles by LiveWall.tiles.collectAsState()
    val tile = tiles[targetDeviceId]
    val track = tile?.track
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var frameSize by remember { mutableStateOf(IntSize.Zero) }
    var lastDrag by remember { mutableStateOf(Offset.Zero) }
    var text by remember { mutableStateOf("") }
    var menuVisible by remember { mutableStateOf(false) }
    var keyboardVisible by remember { mutableStateOf(false) }
    var handleOffset by remember { mutableStateOf(Offset.Zero) }
    val handleSizePx = with(LocalDensity.current) { HANDLE_SIZE.toPx() }
    fun send(event: ControlEvent) = LiveWall.send(targetDeviceId, event)

    // Keep A's screen awake while viewing B, and give B's screen all of A's display.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        val window = view.context.findActivity()?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            view.keepScreenOn = false
            bars?.show(WindowInsetsCompat.Type.systemBars())
            window?.let { WindowCompat.setDecorFitsSystemWindows(it, true) }
        }
    }
    DisposableEffect(targetDeviceId) {
        LiveWall.focus(targetDeviceId)
        onDispose { LiveWall.focus(null) }
    }

    // This phone's Back would otherwise leave the session view by accident.
    BackHandler { menuVisible = !menuVisible }

    // The reason touches do nothing has to be seen, but not left covering the remote screen.
    LaunchedEffect(tile?.canControl) {
        if (tile?.canControl == false) menuVisible = true
    }

    // SCALE_ASPECT_FIT letterboxes the video, so coordinates are relative to the
    // scaled frame centered in the view rather than to the view itself.
    val hasFrame = frameSize.width > 0 && frameSize.height > 0
    val scale = if (hasFrame) minOf(
        viewSize.width.toFloat() / frameSize.width,
        viewSize.height.toFloat() / frameSize.height,
    ) else 1f
    val contentW = if (hasFrame) frameSize.width * scale else viewSize.width.toFloat()
    val contentH = if (hasFrame) frameSize.height * scale else viewSize.height.toFloat()
    fun nx(x: Float) =
        if (contentW > 0f) ((x - (viewSize.width - contentW) / 2f) / contentW).coerceIn(0f, 1f) else 0f
    fun ny(y: Float) =
        if (contentH > 0f) ((y - (viewSize.height - contentH) / 2f) / contentH).coerceIn(0f, 1f) else 0f

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { viewSize = it },
        contentAlignment = Alignment.Center,
    ) {
        // The whole remote screen, as large as fits. The renderer crops the frame to the
        // shape of its view, so the view must take the frame's shape or the remote's status
        // and navigation bars are cut off when the two phones' screens differ.
        if (track != null) {
            val videoModifier = if (hasFrame) {
                Modifier.aspectRatio(frameSize.width.toFloat() / frameSize.height)
            } else {
                Modifier.fillMaxSize()
            }
            VideoSurface(track, videoModifier, onFrameSize = { frameSize = it })
        }

        // Gesture layer over the full screen: taps/drags go to the controlled phone.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(viewSize, frameSize) {
                    detectTapGestures(
                        onTap = { send(ControlEvent.Tap(nx(it.x), ny(it.y))) },
                        onDoubleTap = { send(ControlEvent.DoubleTap(nx(it.x), ny(it.y))) },
                        onLongPress = { send(ControlEvent.LongPress(nx(it.x), ny(it.y))) },
                    )
                }
                .pointerInput(viewSize, frameSize) {
                    detectDragGestures(
                        onDragStart = {
                            lastDrag = it
                            send(ControlEvent.DragStart(nx(it.x), ny(it.y)))
                        },
                        onDrag = { change, _ ->
                            lastDrag = change.position
                            send(ControlEvent.DragMove(nx(change.position.x), ny(change.position.y)))
                        },
                        // Without an end event the target's finger stays down.
                        onDragEnd = { send(ControlEvent.DragEnd(nx(lastDrag.x), ny(lastDrag.y))) },
                        onDragCancel = { send(ControlEvent.DragEnd(nx(lastDrag.x), ny(lastDrag.y))) },
                    )
                },
        )

        when (tile?.status) {
            LinkStatus.WAITING, LinkStatus.CONNECTING ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("Connecting…", color = Color.White, modifier = Modifier.padding(top = 12.dp))
                }
            LinkStatus.LIVE -> Unit
            null -> Unit
            else -> Text(tile?.message ?: "Session ended", color = Color.White)
        }

        // Starts mid-way down the right edge, clear of the remote's status and navigation bars.
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .offset { IntOffset(handleOffset.x.roundToInt(), handleOffset.y.roundToInt()) }
                .size(HANDLE_SIZE)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.35f))
                .pointerInput(viewSize) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        handleOffset = Offset(
                            (handleOffset.x + drag.x).coerceIn(-(viewSize.width - handleSizePx), 0f),
                            (handleOffset.y + drag.y).coerceIn(
                                -(viewSize.height - handleSizePx) / 2f,
                                (viewSize.height - handleSizePx) / 2f,
                            ),
                        )
                    }
                }
                .clickable { menuVisible = !menuVisible },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "Menu",
                tint = Color.White.copy(alpha = 0.7f),
            )
        }

        if (menuVisible) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.75f))
                    .systemBarsPadding()
                    .imePadding()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (tile?.canControl == false) {
                    Text(
                        "View only — turn on control access on that phone to control it.",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (keyboardVisible) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            label = { Text("Type on device") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = { send(ControlEvent.Text(text)); text = "" },
                            enabled = text.isNotEmpty(),
                        ) { Text("Send") }
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // For a controlled phone using swipe navigation, which has no keys to tap.
                    ControlChip("Back") { send(ControlEvent.Nav("back")) }
                    ControlChip("Home") { send(ControlEvent.Nav("home")) }
                    ControlChip("Recent") { send(ControlEvent.Nav("recent")) }
                    IconButton(onClick = { send(ControlEvent.Nav("notifications")) }) {
                        Icon(Icons.Filled.Notifications, "Pull down notifications", tint = Color.White)
                    }
                    IconButton(onClick = { keyboardVisible = !keyboardVisible }) {
                        Icon(Icons.Filled.KeyboardArrowDown, "Keyboard", tint = Color.White)
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        Text("All phones", modifier = Modifier.padding(start = 6.dp))
                    }
                    Button(
                        onClick = { LiveWall.stop(targetDeviceId, "emergency_stop"); onBack() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = null)
                        Text("Stop", modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
    }
}

private val HANDLE_SIZE = 36.dp

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun ControlChip(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    ) { Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium) }
}
