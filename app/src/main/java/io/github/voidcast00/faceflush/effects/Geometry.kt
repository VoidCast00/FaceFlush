package io.github.voidcast00.faceflush.effects

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** ARGB_8888 pixels, row-major. Effects mutate [pixels] in place. */
class PixelBuffer(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(pixels.size == width * height) { "pixels.size != width * height" }
    }
}

data class Pt(val x: Float, val y: Float) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(s: Float) = Pt(x * s, y * s)
    fun dist(o: Pt) = hypot(x - o.x, y - o.y)
}

data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val cx get() = (left + right) / 2f
    val cy get() = (top + bottom) / 2f

    fun scaled(s: Float) = Box(left * s, top * s, right * s, bottom * s)

    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom

    /**
     * Grows the box by [factor] around its centre. Height grows a bit more than width and the box
     * shifts up slightly, because detectors box the face tightly and miss the forehead and hairline.
     */
    fun expanded(factor: Float): Box {
        if (factor <= 1f) return this
        val w = width * factor
        val h = height * (1f + (factor - 1f) * 1.4f)
        val cyShifted = cy - (h - height) * 0.15f
        return Box(cx - w / 2f, cyShifted - h / 2f, cx + w / 2f, cyShifted + h / 2f)
    }

    companion object {
        fun of(x0: Float, y0: Float, x1: Float, y1: Float) =
            Box(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
    }
}

/** Five facial landmarks as YuNet reports them: two eyes, nose tip, two mouth corners. */
data class Landmarks(val eyeA: Pt, val eyeB: Pt, val nose: Pt, val mouthA: Pt, val mouthB: Pt) {
    fun scaled(s: Float) = Landmarks(eyeA * s, eyeB * s, nose * s, mouthA * s, mouthB * s)

    companion object {
        /** Rough landmark positions for a frontal face filling [box]. */
        fun estimate(box: Box): Landmarks {
            fun at(u: Float, v: Float) = Pt(box.left + box.width * u, box.top + box.height * v)
            return Landmarks(
                eyeA = at(0.32f, 0.40f),
                eyeB = at(0.68f, 0.40f),
                nose = at(0.50f, 0.60f),
                mouthA = at(0.36f, 0.78f),
                mouthB = at(0.64f, 0.78f),
            )
        }
    }
}

/** An area to disrupt, in source-image coordinates. */
data class Region(val box: Box, val landmarks: Landmarks? = null, val wholeImage: Boolean = false)

/** Integer pixel bounds of a region inside a buffer, plus an inside test for oval or rectangle shapes. */
internal class Shape private constructor(
    val x0: Int, val y0: Int, val x1: Int, val y1: Int,
    private val box: Box,
    private val oval: Boolean,
) {
    val width get() = x1 - x0
    val height get() = y1 - y0

    private val rx = box.width / 2f
    private val ry = box.height / 2f

    fun contains(x: Int, y: Int): Boolean {
        if (x < x0 || x >= x1 || y < y0 || y >= y1) return false
        if (!oval) return true
        val dx = (x + 0.5f - box.cx) / rx
        val dy = (y + 0.5f - box.cy) / ry
        return dx * dx + dy * dy <= 1f
    }

    companion object {
        /** Returns null when [box] does not overlap the buffer. */
        fun of(box: Box, oval: Boolean, width: Int, height: Int): Shape? {
            val x0 = box.left.toInt().coerceIn(0, width)
            val y0 = box.top.toInt().coerceIn(0, height)
            val x1 = kotlin.math.ceil(box.right).toInt().coerceIn(0, width)
            val y1 = kotlin.math.ceil(box.bottom).toInt().coerceIn(0, height)
            if (x1 - x0 < 1 || y1 - y0 < 1 || box.width < 1f || box.height < 1f) return null
            return Shape(x0, y0, x1, y1, box, oval)
        }
    }
}

internal fun argb(r: Int, g: Int, b: Int): Int =
    (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

internal fun red(c: Int) = (c shr 16) and 0xFF
internal fun green(c: Int) = (c shr 8) and 0xFF
internal fun blue(c: Int) = c and 0xFF

/** Mixes [over] onto [base]; [alpha] 0 keeps base, 1 gives over. */
internal fun blend(base: Int, over: Int, alpha: Float): Int {
    if (alpha >= 1f) return over or (0xFF shl 24)
    val inv = 1f - alpha
    return argb(
        (red(base) * inv + red(over) * alpha).toInt(),
        (green(base) * inv + green(over) * alpha).toInt(),
        (blue(base) * inv + blue(over) * alpha).toInt(),
    )
}
