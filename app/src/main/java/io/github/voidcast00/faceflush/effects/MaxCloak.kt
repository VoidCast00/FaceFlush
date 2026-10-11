package io.github.voidcast00.faceflush.effects

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The on-device recognition models used by "Max privacy" (max_cloak.tflite, made by
 * lab/export_max.py). Arrays are 112x112 NHWC RGB in [0, 1]; see that script for the meaning.
 */
interface CloakModel {
    class Result(val loss: Float, val grad: FloatArray, val embeddings: List<FloatArray>)

    /** Number of embeddings the model returns (one per recognition network inside). */
    val embeddingSizes: List<Int>

    fun step(
        crop: FloatArray, pattern: FloatArray, weight: FloatArray, eps: Float, refs: List<FloatArray>,
        transform: CloakTransform,
    ): Result
}

/** Random change applied before the models look at the face (re-alignment jitter, downscaling). */
data class CloakTransform(val angle: Float, val scale: Float, val shiftX: Float, val shiftY: Float, val down: Float) {
    companion object {
        val IDENTITY = CloakTransform(0f, 1f, 0f, 0f, 1f)

        // Same ranges as random_transform in lab/train_cloak.py
        fun random(rng: Random) = CloakTransform(
            angle = ((rng.nextFloat() * 2 - 1) * 5f * PI / 180).toFloat(),
            scale = 1 + (rng.nextFloat() * 2 - 1) * 0.06f,
            shiftX = (rng.nextFloat() * 2 - 1) * 0.06f,
            shiftY = (rng.nextFloat() * 2 - 1) * 0.06f,
            down = 0.45f + rng.nextFloat() * 0.55f,
        )
    }
}

/**
 * Optimises a cloak pattern for one face (signed-gradient descent with random transforms, as in
 * lab/sim_max.py), then returns it as a canonical field that [cloak] pastes into the photo.
 */
class MaxCloak(private val model: CloakModel) {

    fun optimise(
        buf: PixelBuffer,
        lm: Landmarks,
        eps: Float,
        steps: Int,
        start: CanonicalField?,
        seed: Long,
        eot: Int = 1,
        onStep: (Int) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): CanonicalField {
        val n = CanonicalFace.SIZE
        val crop = alignedCrop(buf, lm)
        val weight = cropWeight(crop)
        val pattern = FloatArray(n * n * 3)
        if (start != null) {
            for (y in 0 until n) for (x in 0 until n) for (c in 0 until 3) {
                pattern[(y * n + x) * 3 + c] = start.sample(c, x.toDouble(), y.toDouble())
            }
        }
        val refs = model.step(crop, pattern, weight, 0f, model.embeddingSizes.map { FloatArray(it) }, CloakTransform.IDENTITY)
            .embeddings
        val rng = Random(seed)
        val stepSize = 10f / steps
        val grad = FloatArray(pattern.size)
        for (i in 0 until steps) {
            // Average the gradient over `eot` random transforms (expectation over transformation)
            grad.fill(0f)
            for (k in 0 until eot) {
                if (isCancelled()) break
                val g = model.step(crop, pattern, weight, eps, refs, CloakTransform.random(rng)).grad
                for (j in grad.indices) grad[j] += g[j]
            }
            if (isCancelled()) break
            for (j in pattern.indices) pattern[j] = (pattern[j] - stepSize * sign(grad[j])).coerceIn(-1f, 1f)
            onStep(i + 1)
        }
        // NHWC -> channel-major for CanonicalField
        val chw = FloatArray(3 * n * n)
        for (y in 0 until n) for (x in 0 until n) for (c in 0 until 3) chw[c * n * n + y * n + x] = pattern[(y * n + x) * 3 + c]
        return CanonicalField(3, n, chw)
    }

    companion object {
        /** The face warped into the canonical 112x112 crop (bilinear), NHWC RGB in [0, 1]. */
        fun alignedCrop(buf: PixelBuffer, lm: Landmarks): FloatArray {
            val n = CanonicalFace.SIZE
            val toImage = CanonicalFace.toCanonical(lm).inverse()
            val out = FloatArray(n * n * 3)
            for (y in 0 until n) for (x in 0 until n) {
                val sx = toImage.mapX(x.toDouble(), y.toDouble())
                val sy = toImage.mapY(x.toDouble(), y.toDouble())
                val c = sampleReflect(buf, sx, sy)
                out[(y * n + x) * 3] = red(c) / 255f
                out[(y * n + x) * 3 + 1] = green(c) / 255f
                out[(y * n + x) * 3 + 2] = blue(c) / 255f
            }
            return out
        }

        /** Edge mask times texture weight on the crop, like apply_crop in lab/faceflush_lab/cloak.py. */
        fun cropWeight(crop: FloatArray): FloatArray {
            val n = CanonicalFace.SIZE
            val lum = FloatArray(n * n) { 0.299f * crop[it * 3] + 0.587f * crop[it * 3 + 1] + 0.114f * crop[it * 3 + 2] }
            val r = 2 // 5x5 window
            fun mean(src: FloatArray, x: Int, y: Int): Float {
                var s = 0f
                for (dy in -r..r) for (dx in -r..r) s += src[(y + dy).coerceIn(0, n - 1) * n + (x + dx).coerceIn(0, n - 1)]
                return s / 25f
            }
            val sq = FloatArray(n * n) { lum[it] * lum[it] }
            return FloatArray(n * n) { i ->
                val x = i % n
                val y = i / n
                val m = mean(lum, x, y)
                val std = sqrt(max(0f, mean(sq, x, y) - m * m))
                val texture = min(1f, 0.35f + 0.65f * std / 0.04f)
                val edge = (minOf(x + 0.5f, y + 0.5f, n - x - 0.5f, n - y - 0.5f) / 10f).coerceIn(0f, 1f)
                edge * texture
            }
        }

        private fun sampleReflect(buf: PixelBuffer, x: Double, y: Double): Int {
            fun reflect(v: Double, size: Int): Double {
                if (size == 1) return 0.0
                val period = 2.0 * (size - 1)
                var m = v % period
                if (m < 0) m += period
                return if (m > size - 1) period - m else m
            }
            val rx = reflect(x, buf.width)
            val ry = reflect(y, buf.height)
            val x0 = floor(rx).toInt()
            val y0 = floor(ry).toInt()
            val x1 = min(x0 + 1, buf.width - 1)
            val y1 = min(y0 + 1, buf.height - 1)
            val fx = (rx - x0).toFloat()
            val fy = (ry - y0).toFloat()
            fun mix(shift: Int): Int {
                fun ch(px: Int, py: Int) = (buf.pixels[py * buf.width + px] shr shift) and 0xFF
                val top = ch(x0, y0) * (1 - fx) + ch(x1, y0) * fx
                val bottom = ch(x0, y1) * (1 - fx) + ch(x1, y1) * fx
                return (top * (1 - fy) + bottom * fy + 0.5f).toInt()
            }
            return argb(mix(16), mix(8), mix(0))
        }
    }
}
