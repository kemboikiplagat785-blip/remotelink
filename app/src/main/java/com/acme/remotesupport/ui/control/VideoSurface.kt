package com.acme.remotesupport.ui.control

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import com.acme.remotesupport.data.webrtc.RtcPeer
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/** Renders [track], letterboxed. [onFrameSize] reports the rotated video size. */
@Composable
fun VideoSurface(
    track: VideoTrack,
    modifier: Modifier = Modifier,
    onFrameSize: (IntSize) -> Unit = {},
) {
    val eglContext = RtcPeer.sharedEglContext ?: return
    key(track) {
        AndroidView(
            factory = { ctx ->
                SurfaceViewRenderer(ctx).apply {
                    init(
                        eglContext,
                        object : RendererCommon.RendererEvents {
                            override fun onFirstFrameRendered() {}
                            override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                                val size = if (rotation % 180 == 0) IntSize(width, height) else IntSize(height, width)
                                post { onFrameSize(size) }
                            }
                        },
                    )
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    setEnableHardwareScaler(true)
                    // The track is already gone if its session ended during composition.
                    runCatching { track.addSink(this) }
                }
            },
            modifier = modifier,
            onRelease = { renderer ->
                runCatching { track.removeSink(renderer) }
                renderer.release()
            },
        )
    }
}
