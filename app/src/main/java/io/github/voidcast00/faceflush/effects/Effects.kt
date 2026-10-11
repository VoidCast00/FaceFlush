package io.github.voidcast00.faceflush.effects

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/** Replaces each block of [blockSize] px inside [shape] with its average colour. */
internal fun pixelate(buf: PixelBuffer, shape: Shape, blockSize: Int) {
    val px = buf.pixels
    val w = buf.width
    var by = shape.y0
    while (by < shape.y1) {
        val byEnd = min(by + blockSize, shape.y1)
        var bx = shape.x0
        while (bx < shape.x1) {
            val bxEnd = min(bx + blockSize, shape.x1)
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            for (y in by until byEnd) for (x in bx until bxEnd) {
                if (!shape.contains(x, y)) continue
                val c = px[y * w + x]
                r += red(c); g += green(c); b += blue(c); n++
            }
            if (n > 0) {
                val avg = argb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
                for (y in by until byEnd) for (x in bx until bxEnd) {
                    if (shape.contains(x, y)) px[y * w + x] = avg
                }
            }
            bx = bxEnd
        }
        by = byEnd
    }
}

/**
 * Blurs the area inside [shape]. Each pass is three box blurs, which is close to a Gaussian
 * blur and costs the same whatever the radius.
 */
internal fun blur(buf: PixelBuffer, shape: Shape, radius: Int, passes: Int) {
    val w = shape.width
    val h = shape.height
    val a = IntArray(w * h)
    for (y in 0 until h) System.arraycopy(buf.pixels, (shape.y0 + y) * buf.width + shape.x0, a, y * w, w)
    val tmp = IntArray(w * h)
    repeat(passes * 3) {
        boxBlurLines(a, tmp, w, h, radius, horizontal = true)
        boxBlurLines(tmp, a, w, h, radius, horizontal = false)
    }
    for (y in 0 until h) for (x in 0 until w) {
        if (shape.contains(shape.x0 + x, shape.y0 + y)) {
            buf.pixels[(shape.y0 + y) * buf.width + shape.x0 + x] = a[y * w + x]
        }
    }
}

private fun boxBlurLines(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val len = if (horizontal) w else h
    val lines = if (horizontal) h else w
    val step = if (horizontal) 1 else w
    val div = 2 * r + 1
    val last = len - 1
    for (line in 0 until lines) {
        val start = if (horizontal) line * w else line
        var sr = 0; var sg = 0; var sb = 0
        for (i in -r..r) {
            val c = src[start + i.coerceIn(0, last) * step]
            sr += red(c); sg += green(c); sb += blue(c)
        }
        for (i in 0 until len) {
            dst[start + i * step] = argb(sr / div, sg / div, sb / div)
            val out = src[start + (i - r).coerceIn(0, last) * step]
            val inn = src[start + (i + r + 1).coerceIn(0, last) * step]
            sr += red(inn) - red(out)
            sg += green(inn) - green(out)
            sb += blue(inn) - blue(out)
        }
    }
}

internal fun fill(buf: PixelBuffer, shape: Shape, color: Int) {
    for (y in shape.y0 until shape.y1) for (x in shape.x0 until shape.x1) {
        if (shape.contains(x, y)) buf.pixels[y * buf.width + x] = color
    }
}

private val rainbow = intArrayOf(
    argb(255, 0, 0), argb(0, 255, 0), argb(0, 0, 255),
    argb(0, 255, 255), argb(255, 0, 255), argb(255, 255, 0),
)

/**
 * Stuck/dead pixel clusters plus optional colour noise and scanlines.
 *
 * Cluster positions are drawn in coordinates relative to the region, and their count comes from
 * the region's size in the source image ([sourceArea]), so a downscaled preview places them in
 * the same spots as the full-size export.
 */
internal fun deadPixels(
    buf: PixelBuffer,
    shape: Shape,
    regionBox: Box,
    sourceArea: Float,
    scale: Float,
    seed: Long,
    densityPercent: Float,
    clusterPx: Float,
    palette: Int,
    noise: Float,
    scanlines: Boolean,
) {
    val px = buf.pixels
    val w = buf.width

    if (noise > 0f) {
        val amp = (noise / 100f * 90f).roundToInt()
        val rng = Random(seed xor 0x5DEECE66DL)
        for (y in shape.y0 until shape.y1) for (x in shape.x0 until shape.x1) {
            if (!shape.contains(x, y)) continue
            val c = px[y * w + x]
            px[y * w + x] = argb(
                red(c) + rng.nextInt(-amp, amp + 1),
                green(c) + rng.nextInt(-amp, amp + 1),
                blue(c) + rng.nextInt(-amp, amp + 1),
            )
        }
    }

    if (scanlines) {
        val spacing = max(2, (6f * scale).roundToInt())
        val thickness = max(1, (2f * scale).roundToInt())
        for (y in shape.y0 until shape.y1) {
            if ((y - shape.y0) % spacing >= thickness) continue
            for (x in shape.x0 until shape.x1) {
                if (shape.contains(x, y)) px[y * w + x] = blend(px[y * w + x], 0, 0.65f)
            }
        }
    }

    val rng = Random(seed)
    val count = (densityPercent / 100f * sourceArea / (clusterPx * clusterPx)).toInt().coerceAtMost(4_000_000)
    val size = max(1, (clusterPx * scale).roundToInt())
    repeat(count) {
        val cx = (regionBox.left + rng.nextFloat() * regionBox.width).toInt()
        val cy = (regionBox.top + rng.nextFloat() * regionBox.height).toInt()
        val pick = rng.nextInt(1 shl 16)
        if (!shape.contains(cx, cy)) return@repeat
        val color = when (palette) {
            0 -> if (pick and 1 == 0) argb(0, 0, 0) else argb(255, 255, 255)
            1 -> rainbow[pick % rainbow.size]
            else -> if (pick % 4 < 2) {
                if (pick and 4 == 0) argb(0, 0, 0) else argb(255, 255, 255)
            } else rainbow[(pick shr 3) % rainbow.size]
        }
        for (y in cy until min(cy + size, shape.y1)) for (x in cx until min(cx + size, shape.x1)) {
            if (shape.contains(x, y)) px[y * w + x] = color
        }
    }
}

/**
 * Paints bars across the eyes, down the nose bridge and across the mouth: the features face
 * detectors and recognisers rely on most.
 */
internal fun landmarkBars(
    buf: PixelBuffer,
    lm: Landmarks,
    thicknessPercent: Float,
    opacity: Float,
    style: Int,
    seed: Long,
) {
    val eyeDist = lm.eyeA.dist(lm.eyeB)
    if (eyeDist < 2f) return
    val t = eyeDist * thicknessPercent / 100f
    val eyeDir = (lm.eyeB - lm.eyeA) * (1f / eyeDist)
    val eyeMid = (lm.eyeA + lm.eyeB) * 0.5f
    val mouthLen = max(lm.mouthA.dist(lm.mouthB), 1f)
    val mouthDir = (lm.mouthB - lm.mouthA) * (1f / mouthLen)

    val segments = listOf(
        Segment(lm.eyeA - eyeDir * (eyeDist * 0.6f), lm.eyeB + eyeDir * (eyeDist * 0.6f), t / 2f),
        Segment(eyeMid, lm.nose, t * 0.3f),
        Segment(lm.mouthA - mouthDir * (mouthLen * 0.3f), lm.mouthB + mouthDir * (mouthLen * 0.3f), t * 0.35f),
    )
    val x0 = segments.minOf { min(it.a.x, it.b.x) - it.halfWidth }.toInt().coerceIn(0, buf.width)
    val x1 = segments.maxOf { max(it.a.x, it.b.x) + it.halfWidth }.toInt().plus(1).coerceIn(0, buf.width)
    val y0 = segments.minOf { min(it.a.y, it.b.y) - it.halfWidth }.toInt().coerceIn(0, buf.height)
    val y1 = segments.maxOf { max(it.a.y, it.b.y) + it.halfWidth }.toInt().plus(1).coerceIn(0, buf.height)

    val cell = max(1f, eyeDist * 0.08f)
    val alpha = (opacity / 100f).coerceIn(0f, 1f)
    val rng = Random(seed)
    for (y in y0 until y1) for (x in x0 until x1) {
        val p = Pt(x + 0.5f, y + 0.5f)
        if (segments.none { it.distanceTo(p) <= it.halfWidth }) continue
        val color = when (style) {
            0 -> argb(0, 0, 0)
            1 -> if (((x / cell).toInt() + (y / cell).toInt()) % 2 == 0) argb(0, 0, 0) else argb(255, 255, 255)
            else -> argb(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256))
        }
        val i = y * buf.width + x
        buf.pixels[i] = blend(buf.pixels[i], color, alpha)
    }
}

private class Segment(val a: Pt, val b: Pt, val halfWidth: Float) {
    private val d = b - a
    private val lenSq = d.x * d.x + d.y * d.y

    fun distanceTo(p: Pt): Float {
        if (lenSq == 0f) return p.dist(a)
        val t = (((p.x - a.x) * d.x + (p.y - a.y) * d.y) / lenSq).coerceIn(0f, 1f)
        return p.dist(a + d * t)
    }
}
