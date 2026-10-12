package io.github.voidcast00.faceflush.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.annotation.RawRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.voidcast00.faceflush.R
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.random.Random

private const val TICK_MS = 140L

/** Animation clock shared by the glitch drawings: ticks every [TICK_MS] while [running], else stays put. */
@Composable
fun rememberGlitchTick(running: Boolean = true): Int {
    val tick = remember { mutableIntStateOf(0) }
    LaunchedEffect(running) {
        while (running) {
            delay(TICK_MS)
            tick.intValue++
        }
    }
    return tick.intValue
}

/**
 * ASCII art drawn on a fixed character grid, so every line lines up whatever font the device
 * uses. [style] decides which cells get glitch blocks in [accent] (see [GlitchStyle]).
 *
 * [faceOnly] crops to the face for small pictures, [fade] fades the art out towards the bottom,
 * and every change of [burstKey] plays a short burst of blocks over the face.
 */
@Composable
fun AsciiArt(
    color: Color,
    accent: Color,
    modifier: Modifier = Modifier,
    style: GlitchStyle = GlitchStyle.Band,
    @RawRes art: Int = R.raw.ascii_portrait,
    faceOnly: Boolean = false,
    fade: Boolean = !faceOnly,
    animate: Boolean = true,
    burstKey: Any? = null,
) {
    val context = LocalContext.current
    val grid = remember(art) {
        AsciiGrid(context.resources.openRawResource(art).bufferedReader().use { it.readLines() })
    }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE } }
    val tick = rememberGlitchTick(animate)
    val burst = remember { Animatable(0f) }
    LaunchedEffect(burstKey) {
        if (burstKey != null) {
            burst.snapTo(1f)
            burst.animateTo(0f, tween(350))
        }
    }

    val rows = if (faceOnly) grid.faceRows else 0 until grid.rows
    val cols = if (faceOnly) grid.faceCols else 0 until grid.columns
    // The art was generated for cells twice as tall as they are wide
    Canvas(
        modifier
            .aspectRatio(cols.count() / (rows.count() * 2f))
            .clipToBounds()
            .graphicsLayer { if (fade) compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        val cellW = size.width / cols.count()
        val cellH = cellW * 2f
        paint.color = color.toArgb()
        paint.textSize = 100f
        paint.textSize = 100f * cellW / paint.measureText("M")
        val baseline = (cellH - paint.fontMetrics.ascent - paint.fontMetrics.descent) / 2f
        translate(-cols.first * cellW, -rows.first * cellH) {
            drawIntoCanvas { canvas ->
                rows.forEach { r -> canvas.nativeCanvas.drawText(grid.text[r], 0f, r * cellH + baseline, paint) }
            }
            fun block(cell: GlitchCell, strength: Float = 1f) {
                val base = cell.hue?.let { Color.hsv(it, 0.55f, 0.95f, accent.alpha) } ?: accent
                drawRect(
                    base.copy(alpha = base.alpha * cell.alpha * strength),
                    topLeft = Offset(cell.col * cellW, cell.row * cellH),
                    size = Size(cellW + 0.5f, cellH + 0.5f),
                )
            }
            style.cells(grid, tick).forEach { block(it) }
            if (burst.value > 0f) burstCells(grid, (burst.value * 1000).toInt()).forEach { block(it, burst.value) }
        }
        if (fade) {
            drawRect(Brush.verticalGradient(0.5f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
        }
    }
}

/**
 * Glitch blocks sweeping across a button while [active] (for example while saving), and a short
 * burst over the whole button when it stops.
 */
@Composable
fun GlitchSweep(active: Boolean, color: Color, modifier: Modifier = Modifier) {
    val tick = rememberGlitchTick(active)
    val burst = remember { Animatable(0f) }
    val wasActive = remember { mutableIntStateOf(0) }
    LaunchedEffect(active) {
        if (active) {
            wasActive.intValue = 1
        } else if (wasActive.intValue == 1) {
            wasActive.intValue = 0
            burst.snapTo(1f)
            burst.animateTo(0f, tween(450))
        }
    }
    if (!active && burst.value == 0f) return
    Canvas(modifier.fillMaxSize()) {
        val cellW = 5.dp.toPx()
        val cellH = cellW * 2
        val cols = (size.width / cellW).toInt() + 1
        val rows = (size.height / cellH).toInt() + 1
        val rng = Random(if (active) tick else (burst.value * 1000).toInt())
        val head = tick * 2 % (cols + 10) - 5
        for (r in 0 until rows) for (c in 0 until cols) {
            val alpha = if (active) {
                val d = abs(c - head)
                if (d <= 4 && rng.nextFloat() < 0.8f) (1f - d / 5f) * 0.45f else if (rng.nextFloat() < 0.03f) 0.2f else 0f
            } else if (rng.nextFloat() < 0.3f) 0.4f * burst.value else 0f
            if (alpha > 0f) {
                drawRect(color.copy(alpha = alpha), Offset(c * cellW, r * cellH), Size(cellW + 0.5f, cellH + 0.5f))
            }
        }
    }
}
