package io.github.voidcast00.faceflush.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.voidcast00.faceflush.EditorState
import io.github.voidcast00.faceflush.R
import io.github.voidcast00.faceflush.effects.RegionMode
import kotlin.math.abs
import kotlin.math.min
import io.github.voidcast00.faceflush.effects.Box as RegionBox

/**
 * The photo fitted into the available space, with face boxes drawn on top. When [interactive],
 * tapping a face skips or includes it, tapping a drawn area removes it and dragging draws a new one.
 */
@Composable
fun PhotoView(
    state: EditorState,
    showOriginal: Boolean,
    showBoxes: Boolean,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    onToggleFace: (Int) -> Unit = {},
    onRemoveManual: (Int) -> Unit = {},
    onAddManual: (RegionBox) -> Unit = {},
) {
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf(Offset.Zero) }
    val current by rememberUpdatedState(state)

    val bitmap = (if (showOriginal) state.original else state.preview) ?: return
    val image = remember(bitmap) { bitmap.asImageBitmap() }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // fit = view pixels per source-image pixel
        val fit = min(
            constraints.maxWidth.toFloat() / state.sourceWidth,
            constraints.maxHeight.toFloat() / state.sourceHeight,
        )
        val density = LocalDensity.current
        val width = with(density) { (state.sourceWidth * fit).toDp() }
        val height = with(density) { (state.sourceHeight * fit).toDp() }
        val primary = MaterialTheme.colorScheme.primary
        val tertiary = MaterialTheme.colorScheme.tertiary
        val minDragPx = with(density) { 16.dp.toPx() }

        val gestures = if (!interactive) Modifier else Modifier
            .pointerInput(fit) {
                detectTapGestures { p ->
                    val s = current
                    if (s.regionMode == RegionMode.WHOLE_IMAGE) return@detectTapGestures
                    val sx = p.x / fit
                    val sy = p.y / fit
                    val manual = s.manualBoxes.indexOfLast { it.contains(sx, sy) }
                    if (manual >= 0) {
                        onRemoveManual(manual)
                        return@detectTapGestures
                    }
                    if (s.regionMode == RegionMode.FACES) {
                        val face = s.faces.indexOfLast { it.face.box.contains(sx, sy) }
                        if (face >= 0) onToggleFace(face)
                    }
                }
            }
            .pointerInput(fit) {
                detectDragGestures(
                    onDragStart = { dragStart = it; dragEnd = it },
                    onDrag = { change, amount ->
                        change.consume()
                        dragEnd += amount
                    },
                    onDragEnd = {
                        val start = dragStart
                        if (start != null && abs(dragEnd.x - start.x) > minDragPx &&
                            abs(dragEnd.y - start.y) > minDragPx
                        ) {
                            val endX = dragEnd.x.coerceIn(0f, size.width.toFloat())
                            val endY = dragEnd.y.coerceIn(0f, size.height.toFloat())
                            onAddManual(RegionBox.of(start.x / fit, start.y / fit, endX / fit, endY / fit))
                        }
                        dragStart = null
                    },
                    onDragCancel = { dragStart = null },
                )
            }

        Box(Modifier.size(width, height).clip(MaterialTheme.shapes.medium)) {
            Image(
                image,
                contentDescription = stringResource(R.string.preview_description),
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
            Canvas(Modifier.fillMaxSize().then(gestures)) {
                if (showBoxes && state.regionMode != RegionMode.WHOLE_IMAGE) {
                    if (state.regionMode == RegionMode.FACES) {
                        state.faces.forEach { item ->
                            drawRegionBox(item.face.box, fit, if (item.enabled) primary else Color.Gray, !item.enabled)
                        }
                    }
                    state.manualBoxes.forEach { drawRegionBox(it, fit, tertiary, dashed = false) }
                }
                dragStart?.let { start ->
                    drawRect(
                        tertiary,
                        topLeft = Offset(min(start.x, dragEnd.x), min(start.y, dragEnd.y)),
                        size = Size(abs(dragEnd.x - start.x), abs(dragEnd.y - start.y)),
                        style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))),
                    )
                }
            }
            if (state.detecting) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }
}

/** Compare (press and hold), boxes on/off and, when [onExpand] is set, fullscreen. */
@Composable
fun PreviewToolbar(
    showBoxes: Boolean,
    onShowBoxesChange: (Boolean) -> Unit,
    onComparePressed: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onExpand: (() -> Unit)? = null,
    onDark: Boolean = false,
) {
    Row(modifier, horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        var comparing by remember { mutableStateOf(false) }
        ToolButton(
            icon = Icons.Outlined.Compare,
            label = stringResource(R.string.tool_compare),
            active = comparing,
            onDark = onDark,
            modifier = Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    comparing = true
                    onComparePressed(true)
                    waitForUpOrCancellation()
                    comparing = false
                    onComparePressed(false)
                }
            },
        )
        ToolButton(
            icon = Icons.Outlined.CropFree,
            label = stringResource(R.string.tool_boxes),
            active = showBoxes,
            onDark = onDark,
            modifier = Modifier.toggleable(value = showBoxes, role = Role.Switch, onValueChange = onShowBoxesChange),
        )
        if (onExpand != null) {
            ToolButton(
                icon = Icons.Outlined.OpenInFull,
                label = stringResource(R.string.tool_expand),
                active = false,
                onDark = onDark,
                modifier = Modifier.clickable(role = Role.Button, onClick = onExpand),
            )
        }
    }
}

/** Icon in a pill that fills in while active, label underneath (like a navigation bar item). */
@Composable
private fun ToolButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    modifier: Modifier,
    onDark: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val content = when {
        active -> colors.onSecondaryContainer
        onDark -> Color.White
        else -> colors.onSurfaceVariant
    }
    Column(
        Modifier.clip(MaterialTheme.shapes.medium).then(modifier).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .width(56.dp)
                .height(32.dp)
                .clip(CircleShape)
                .background(if (active) colors.secondaryContainer else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (onDark) Color.White else colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Full-screen view with pinch to zoom, drag to pan and double tap to reset. */
@Composable
fun FullscreenPreview(
    state: EditorState,
    showBoxes: Boolean,
    onShowBoxesChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        var showOriginal by remember { mutableStateOf(false) }
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            scale = 1f
                            offset = Offset.Zero
                        })
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    },
            ) {
                PhotoView(
                    state = state,
                    showOriginal = showOriginal,
                    showBoxes = showBoxes,
                    interactive = false,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            }
            // Controls sit at the top: on some phones (HyperOS) a dialog's bottom edge ends up
            // under the navigation bar, where the buttons can't be reached
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .statusBarsPadding()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PreviewToolbar(
                    showBoxes = showBoxes,
                    onShowBoxesChange = onShowBoxesChange,
                    onComparePressed = { showOriginal = it },
                    onDark = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.padding(end = 8.dp)) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.close), tint = Color.White)
                }
            }
        }
    }
}

private fun DrawScope.drawRegionBox(box: RegionBox, fit: Float, color: Color, dashed: Boolean) {
    val topLeft = Offset(box.left * fit, box.top * fit)
    val size = Size(box.width * fit, box.height * fit)
    val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null
    // Dark halo first so the box stays visible on light photos
    drawRect(Color.Black.copy(alpha = 0.5f), topLeft, size, style = Stroke(4.dp.toPx(), pathEffect = effect))
    drawRect(color, topLeft, size, style = Stroke(2.dp.toPx(), pathEffect = effect))
    if (dashed) {
        drawLine(color, topLeft, Offset(topLeft.x + size.width, topLeft.y + size.height), 2.dp.toPx())
        drawLine(color, Offset(topLeft.x + size.width, topLeft.y), Offset(topLeft.x, topLeft.y + size.height), 2.dp.toPx())
    }
}
