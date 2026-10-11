package io.github.voidcast00.faceflush.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.annotation.RawRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlin.random.Random

private const val GLITCH_GLYPHS = "█▓▒░"
private val glitchAlphas = floatArrayOf(1f, 0.7f, 0.45f, 0.2f)

/** Text art split into plain lines (glitch glyphs blanked out) and the grid cells of the glitch band. */
private class AsciiGrid(lines: List<String>) {
    val columns = lines.maxOf { it.length }.coerceAtLeast(1)
    val rows = lines.size
    val text = lines.map { line -> line.map { if (it in GLITCH_GLYPHS) ' ' else it }.joinToString("") }
    val glitchCells = lines.flatMapIndexed { r, line ->
        line.mapIndexedNotNull { c, ch -> if (ch in GLITCH_GLYPHS) r to c else null }
    }
}

/**
 * Background ASCII art drawn on a fixed character grid, so every line lines up whatever font the
 * device uses. The glitch band over the eyes is drawn as blocks that flicker in [accent], and the
 * art fades out towards the bottom.
 */
@Composable
fun AsciiArt(
    @RawRes art: Int,
    color: Color,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val grid = remember(art) {
        AsciiGrid(context.resources.openRawResource(art).bufferedReader().use { it.readLines() })
    }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE } }
    val tick = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(140)
            tick.intValue++
        }
    }

    // The art was generated for cells twice as tall as they are wide
    Canvas(
        modifier
            .aspectRatio(grid.columns / (grid.rows * 2f))
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        val cellW = size.width / grid.columns
        val cellH = cellW * 2f
        paint.color = color.toArgb()
        paint.textSize = 100f
        paint.textSize = 100f * cellW / paint.measureText("M")
        val baseline = (cellH - paint.fontMetrics.ascent - paint.fontMetrics.descent) / 2f
        drawIntoCanvas { canvas ->
            grid.text.forEachIndexed { r, line ->
                canvas.nativeCanvas.drawText(line, 0f, r * cellH + baseline, paint)
            }
        }

        val rng = Random(tick.intValue)
        grid.glitchCells.forEach { (r, c) ->
            val alpha = glitchAlphas[rng.nextInt(glitchAlphas.size)]
            drawRect(
                accent.copy(alpha = accent.alpha * alpha),
                topLeft = Offset(c * cellW, r * cellH),
                size = Size(cellW + 0.5f, cellH + 0.5f),
            )
        }

        drawRect(Brush.verticalGradient(0.5f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
    }
}
