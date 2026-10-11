package io.github.voidcast00.faceflush.effects

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The trained patterns behind the "Invisible" technique, made in the lab (see lab/README.md).
 * [cloak]: 3 channels (RGB) in [-1, 1]. [morph]: 2 channels, displacement in canonical pixels.
 */
class InvisibleAssets(val cloak: CanonicalField?, val morph: CanonicalField?)

/** A cloak optimised for one particular face by "Max privacy", and the strength it was made for. */
class FacePattern(val field: CanonicalField, val eps: Float)

// Must match lab/faceflush_lab/cloak.py
private const val EDGE_FEATHER = 10.0
private const val TEXTURE_WINDOW = 5.0
private const val TEXTURE_FLOOR = 0.35f
private const val TEXTURE_FULL = 0.04f

/** Pixel bounds (clamped to the buffer) covered by the canonical square of a face. */
private fun canonicalBounds(toImage: Affine, buf: PixelBuffer): IntArray {
    val s = CanonicalFace.SIZE.toDouble()
    val xs = doubleArrayOf(toImage.mapX(0.0, 0.0), toImage.mapX(s, 0.0), toImage.mapX(0.0, s), toImage.mapX(s, s))
    val ys = doubleArrayOf(toImage.mapY(0.0, 0.0), toImage.mapY(s, 0.0), toImage.mapY(0.0, s), toImage.mapY(s, s))
    return intArrayOf(
        floor(xs.min()).toInt().coerceIn(0, buf.width), floor(ys.min()).toInt().coerceIn(0, buf.height),
        ceil(xs.max()).toInt().plus(1).coerceIn(0, buf.width), ceil(ys.max()).toInt().plus(1).coerceIn(0, buf.height),
    )
}

/**
 * Adds the canonical cloak [pattern] onto the face with landmarks [lm] (buffer coordinates):
 * x' = x + eps * edgeMask * textureWeight(x) * pattern, as in lab/faceflush_lab/cloak.py.
 */
internal fun cloak(buf: PixelBuffer, lm: Landmarks, pattern: CanonicalField, eps: Float) {
    if (eps <= 0f) return
    val toCanon = CanonicalFace.toCanonical(lm)
    val (x0, y0, x1, y1) = canonicalBounds(toCanon.inverse(), buf)
    val w = x1 - x0
    val h = y1 - y0
    if (w < 2 || h < 2) return

    val weight = textureWeight(buf, x0, y0, w, h, max(3, (TEXTURE_WINDOW / toCanon.scale).roundToInt() or 1))
    val size = CanonicalFace.SIZE.toDouble()
    for (y in y0 until y1) for (x in x0 until x1) {
        val cx = toCanon.mapX(x.toDouble(), y.toDouble())
        val cy = toCanon.mapY(x.toDouble(), y.toDouble())
        if (cx < -1 || cy < -1 || cx > size || cy > size) continue
        val edge = (minOf(cx + 0.5, cy + 0.5, size - cx - 0.5, size - cy - 0.5) / EDGE_FEATHER).coerceIn(0.0, 1.0).toFloat()
        if (edge <= 0f) continue
        val k = eps * edge * weight[(y - y0) * w + (x - x0)] * 255f
        val i = y * buf.width + x
        val c = buf.pixels[i]
        buf.pixels[i] = argb(
            (red(c) + k * pattern.sample(0, cx, cy)).roundToInt(),
            (green(c) + k * pattern.sample(1, cx, cy)).roundToInt(),
            (blue(c) + k * pattern.sample(2, cx, cy)).roundToInt(),
        )
    }
}

/** Local luminance standard deviation mapped to [TEXTURE_FLOOR, 1] over a [w] x [h] window of [buf]. */
private fun textureWeight(buf: PixelBuffer, x0: Int, y0: Int, w: Int, h: Int, window: Int): FloatArray {
    val lum = FloatArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        val c = buf.pixels[(y0 + y) * buf.width + x0 + x]
        lum[y * w + x] = (0.299f * red(c) + 0.587f * green(c) + 0.114f * blue(c)) / 255f
    }
    val mean = boxMean(lum, w, h, window)
    val meanSq = boxMean(FloatArray(lum.size) { lum[it] * lum[it] }, w, h, window)
    return FloatArray(lum.size) {
        val std = sqrt(max(0f, meanSq[it] - mean[it] * mean[it]))
        min(1f, TEXTURE_FLOOR + (1 - TEXTURE_FLOOR) * std / TEXTURE_FULL)
    }
}

/** Box filter with edge replication, separable. */
private fun boxMean(src: FloatArray, w: Int, h: Int, window: Int): FloatArray {
    val r = window / 2
    val tmp = FloatArray(src.size)
    val out = FloatArray(src.size)
    for (y in 0 until h) {
        var sum = 0f
        for (i in -r..r) sum += src[y * w + i.coerceIn(0, w - 1)]
        for (x in 0 until w) {
            tmp[y * w + x] = sum / window
            sum += src[y * w + (x + r + 1).coerceIn(0, w - 1)] - src[y * w + (x - r).coerceIn(0, w - 1)]
        }
    }
    for (x in 0 until w) {
        var sum = 0f
        for (i in -r..r) sum += tmp[i.coerceIn(0, h - 1) * w + x]
        for (y in 0 until h) {
            out[y * w + x] = sum / window
            sum += tmp[(y + r + 1).coerceIn(0, h - 1) * w + x] - tmp[(y - r).coerceIn(0, h - 1) * w + x]
        }
    }
    return out
}

/**
 * Warps the face with the canonical displacement [field] scaled by [amount]:
 * out(p) = in(p - A⁻¹ · field(M·p)), as in lab/faceflush_lab/morph.py.
 */
internal fun morph(buf: PixelBuffer, lm: Landmarks, field: CanonicalField, amount: Float) {
    if (amount <= 0f) return
    val toCanon = CanonicalFace.toCanonical(lm)
    val inv = toCanon.inverse()
    val (x0, y0, x1, y1) = canonicalBounds(inv, buf)
    if (x1 - x0 < 2 || y1 - y0 < 2) return
    val src = buf.pixels.copyOf()
    for (y in y0 until y1) for (x in x0 until x1) {
        // The field's pixel centres sit at +0.5, hence the shift (matches cv2.remap in the lab)
        val cx = toCanon.mapX(x.toDouble(), y.toDouble()) - 0.5
        val cy = toCanon.mapY(x.toDouble(), y.toDouble()) - 0.5
        val fx = field.sample(0, cx, cy) * amount
        val fy = field.sample(1, cx, cy) * amount
        if (fx == 0f && fy == 0f) continue
        val sx = x - (inv.a * fx + inv.b * fy)
        val sy = y - (inv.d * fx + inv.e * fy)
        buf.pixels[y * buf.width + x] = bilinear(src, buf.width, buf.height, sx, sy)
    }
}

private fun bilinear(px: IntArray, w: Int, h: Int, x: Double, y: Double): Int {
    val xc = x.coerceIn(0.0, w - 1.0)
    val yc = y.coerceIn(0.0, h - 1.0)
    val x0 = xc.toInt()
    val y0 = yc.toInt()
    val x1 = min(x0 + 1, w - 1)
    val y1 = min(y0 + 1, h - 1)
    val fx = (xc - x0).toFloat()
    val fy = (yc - y0).toFloat()
    fun ch(c: Int, shift: Int) = (c shr shift) and 0xFF
    fun mix(shift: Int): Int {
        val top = ch(px[y0 * w + x0], shift) * (1 - fx) + ch(px[y0 * w + x1], shift) * fx
        val bottom = ch(px[y1 * w + x0], shift) * (1 - fx) + ch(px[y1 * w + x1], shift) * fx
        return (top * (1 - fy) + bottom * fy).roundToInt()
    }
    return argb(mix(16), mix(8), mix(0))
}
