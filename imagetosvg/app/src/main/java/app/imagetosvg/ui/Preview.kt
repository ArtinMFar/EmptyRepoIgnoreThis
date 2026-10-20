package app.imagetosvg.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The picture, fitted to the screen width. Pinch to zoom (pixels stay sharp once they're
 * big enough to see), double-tap to reset, and hold the button to see the original.
 */
@Composable
fun PreviewCard(
    image: ImageBitmap,
    original: ImageBitmap,
    label: String,
    /** Width / height of the real image (previews may be scaled copies). */
    aspect: Float,
    modifier: Modifier = Modifier,
    /** Read while drawing, so changing it redraws a bitmap whose pixels were changed in place. */
    version: Int = 0,
    crispWhenEnlarged: Boolean = true,
) {
    var comparing by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val shown = if (comparing) original else image
    val checkerA = MaterialTheme.colorScheme.surfaceContainerHighest
    val checkerB = MaterialTheme.colorScheme.surfaceContainerLowest

    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val height = (maxWidth / aspect).coerceAtMost(MaxPreviewHeight)
        val width = height * aspect
        Box(
            Modifier
                .size(width, height)
                .clip(RoundedCornerShape(20.dp))
                .drawBehind {
                    // Checkerboard, so transparent parts of the image read as transparent.
                    val cell = 8.dp.toPx()
                    drawRect(checkerB)
                    var y = 0f
                    var row = 0
                    while (y < size.height) {
                        var x = if (row % 2 == 0) 0f else cell
                        while (x < size.width) {
                            drawRect(checkerA, Offset(x, y), Size(cell, cell))
                            x += cell * 2
                        }
                        y += cell
                        row++
                    }
                }
                .pointerInput(Unit) {
                    // Pinch always zooms; one finger only pans once zoomed in, so the page
                    // still scrolls normally when the gesture starts on the picture.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val fingers = event.changes.count { it.pressed }
                            if (fingers >= 2 || zoom > 1f) {
                                val newZoom = (zoom * event.calculateZoom()).coerceIn(1f, 64f)
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val c = event.calculateCentroid(useCurrent = true) - center
                                var o = c - (c - offset) * (newZoom / zoom) + event.calculatePan()
                                val maxX = size.width * (newZoom - 1) / 2
                                val maxY = size.height * (newZoom - 1) / 2
                                o = Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
                                zoom = newZoom
                                offset = o
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        zoom = 1f
                        offset = Offset.Zero
                    })
                },
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = offset.x
                        translationY = offset.y
                    },
            ) {
                @Suppress("UNUSED_EXPRESSION") version
                val enlarge = size.width * zoom / shown.width
                drawImage(
                    shown,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = if (crispWhenEnlarged && enlarge >= 2f) FilterQuality.None else FilterQuality.Low,
                )
            }
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
            ) {
                Text(
                    if (comparing) "Original" else label,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            if (image !== original) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                        .semantics { contentDescription = "Hold to compare with the original" }
                        .pointerInput(Unit) {
                            detectTapGestures(onPress = {
                                comparing = true
                                tryAwaitRelease()
                                comparing = false
                            })
                        },
                ) {
                    Icon(
                        Icons.Rounded.Compare,
                        contentDescription = null,
                        modifier = Modifier.padding(8.dp).size(20.dp),
                    )
                }
            }
        }
    }
}

private val MaxPreviewHeight = 420.dp
