package io.github.voidcast00.faceflush.ui

import io.github.voidcast00.faceflush.effects.Technique
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

private const val GLITCH_GLYPHS = "█▓▒░"

/** Characters of the art from light to dark, for the "how much ink is here" lookups. */
private const val INK = " .:-=+*o#%@"

private val flickerAlphas = floatArrayOf(1f, 0.7f, 0.45f, 0.2f)

/** One block drawn over the art. [hue] (degrees) makes it a rainbow block; null uses the accent colour. */
data class GlitchCell(val row: Int, val col: Int, val alpha: Float, val hue: Float? = null)

/**
 * Text art split into plain lines (glitch glyphs blanked out) and the cells of its glitch band.
 * The band sits over the eyes, so the face oval is estimated from it: centred just below the band,
 * about as wide as the band and ten rows tall in each direction (cells are twice as tall as wide).
 */
class AsciiGrid(lines: List<String>) {
    val columns = lines.maxOf { it.length }.coerceAtLeast(1)
    val rows = lines.size
    val text = lines.map { line -> line.map { if (it in GLITCH_GLYPHS) ' ' else it }.joinToString("") }
    val band = lines.flatMapIndexed { r, line ->
        line.mapIndexedNotNull { c, ch -> if (ch in GLITCH_GLYPHS) r to c else null }
    }

    val eyeRow = if (band.isEmpty()) rows / 2f else band.map { it.first }.average().toFloat()
    private val bandLeft = band.minOfOrNull { it.second } ?: 0
    private val bandWidth = band.maxOfOrNull { it.second }?.let { it - bandLeft + 1 } ?: columns
    val centerRow = eyeRow + 0.5f
    val centerCol = bandLeft + bandWidth * 0.65f
    val radiusRows = 10f
    val radiusCols = bandWidth * 0.7f
    val mouthRow = (eyeRow + 7).roundToInt()

    fun inFace(r: Int, c: Int): Boolean {
        val dr = (r - centerRow) / radiusRows
        val dc = (c - centerCol) / radiusCols
        return dr * dr + dc * dc <= 1f
    }

    val face = (0 until rows).flatMap { r -> (0 until columns).filter { c -> inFace(r, c) }.map { c -> r to c } }

    /** Ink (0 = blank, 1 = darkest character) at a cell; blank outside the art. */
    fun ink(r: Int, c: Int): Float {
        val ch = text.getOrNull(r)?.getOrNull(c) ?: return 0f
        val i = INK.indexOf(ch)
        return if (i < 0) 0.5f else i / (INK.length - 1f)
    }

    /** A window around the face (rows, columns) for small pictures. */
    val faceRows = (centerRow - radiusRows - 2).toInt().coerceAtLeast(0)..(centerRow + radiusRows + 2).toInt().coerceAtMost(rows - 1)
    val faceCols = (centerCol - radiusCols - 4).toInt().coerceAtLeast(0)..(centerCol + radiusCols + 4).toInt().coerceAtMost(columns - 1)
}

/** How the glitch blocks cover the art. Every style is a pure function of the animation tick. */
sealed interface GlitchStyle {
    /** The home-screen look: the band over the eyes flickers. */
    data object Band : GlitchStyle

    /** Max privacy at work: the glitch spreads from the eyes over the whole face as [fraction] grows. */
    data class Progress(val fraction: Float, val rainbow: Boolean = false) : GlitchStyle

    /** Looking for faces: a bright line sweeps up and down the art like a scanner. */
    data object Scan : GlitchStyle

    /** The face hidden the way [technique] would hide it. */
    data class Hidden(val technique: Technique) : GlitchStyle
}

fun GlitchStyle.cells(grid: AsciiGrid, tick: Int): List<GlitchCell> {
    val rng = Random(tick)
    fun flicker() = flickerAlphas[rng.nextInt(flickerAlphas.size)]
    fun List<Pair<Int, Int>>.flickering(hue: Boolean = false) =
        map { (r, c) -> GlitchCell(r, c, flicker(), if (hue) rng.nextFloat() * 360f else null) }

    return when (this) {
        GlitchStyle.Band -> grid.band.flickering()

        is GlitchStyle.Progress -> {
            // Furthest face row from the eyes, so that 100% covers the whole oval
            val full = abs(grid.centerRow - grid.eyeRow) + grid.radiusRows + 1
            val reach = fraction.coerceIn(0f, 1f) * full
            (grid.band + grid.face.filter { (r, _) -> abs(r - grid.eyeRow) <= reach }).distinct().flickering(rainbow)
        }

        GlitchStyle.Scan -> {
            val period = 2 * (grid.rows - 1).coerceAtLeast(1)
            val phase = tick % period
            val line = if (phase < grid.rows) phase else period - phase
            val sweep = (line - 1..line + 1).flatMap { r ->
                (0 until grid.columns).filter { c -> grid.ink(r, c) > 0f }.map { c ->
                    GlitchCell(r, c, if (r == line) 1f else 0.45f)
                }
            }
            sweep + grid.band.map { (r, c) -> GlitchCell(r, c, flicker() * 0.35f) }
        }

        is GlitchStyle.Hidden -> hidden(technique, grid, rng, ::flicker)
    }
}

private fun hidden(technique: Technique, grid: AsciiGrid, rng: Random, flicker: () -> Float): List<GlitchCell> =
    when (technique) {
        Technique.PIXELATE -> grid.face.map { (r, c) ->
            // Coarse blocks of 4 x 2 cells, each as dark as the art underneath it
            val r0 = r / 2 * 2
            val c0 = c / 4 * 4
            val ink = (r0 until r0 + 2).sumOf { rr -> (c0 until c0 + 4).sumOf { cc -> grid.ink(rr, cc).toDouble() } } / 8
            GlitchCell(r, c, (0.2f + 0.8f * ink.toFloat()) * (0.85f + 0.15f * rng.nextFloat()))
        }

        Technique.BLUR -> grid.face.map { (r, c) ->
            val ink = (r - 1..r + 1).sumOf { rr -> (c - 3..c + 3).sumOf { cc -> grid.ink(rr, cc).toDouble() } } / 21
            GlitchCell(r, c, 0.15f + 0.5f * ink.toFloat())
        }

        Technique.MASK -> grid.face.map { (r, c) -> GlitchCell(r, c, 1f) }

        Technique.DEAD_PIXELS -> grid.face.mapNotNull { (r, c) ->
            when {
                rng.nextFloat() < 0.18f -> GlitchCell(r, c, flicker())
                (r + rng.nextInt(2)) % 3 == 0 -> GlitchCell(r, c, 0.2f) // scanlines
                else -> null
            }
        }

        Technique.LANDMARKS -> {
            // Bars over the eyes, nose and mouth, like the Feature bars technique
            val eyes = grid.band.map { it.first }.distinct()
            val bars = eyes.map { it to grid.radiusCols * 0.8f } +
                listOf((grid.eyeRow + 4).roundToInt() to grid.radiusCols * 0.2f, grid.mouthRow to grid.radiusCols * 0.45f)
            bars.flatMap { (r, half) ->
                ((grid.centerCol - half).roundToInt()..(grid.centerCol + half).roundToInt()).map { c -> GlitchCell(r, c, 1f) }
            }
        }

        Technique.INVISIBLE -> grid.band.map { (r, c) -> GlitchCell(r, c, flicker()) }
    }

/** A short random burst over the face, played when something is picked or finished. */
fun burstCells(grid: AsciiGrid, seed: Int): List<GlitchCell> {
    val rng = Random(seed)
    return grid.face.filter { rng.nextFloat() < 0.35f }.map { (r, c) -> GlitchCell(r, c, flickerAlphas[rng.nextInt(4)]) }
}
