package io.github.voidcast00.faceflush.effects

/**
 * 2x3 affine matrix [a b c; d e f] mapping (x, y) to (a*x + b*y + c, d*x + e*y + f).
 */
data class Affine(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
    fun mapX(x: Double, y: Double) = a * x + b * y + c
    fun mapY(x: Double, y: Double) = d * x + e * y + f

    /** Uniform scale of a similarity transform. */
    val scale get() = kotlin.math.sqrt(kotlin.math.abs(a * e - b * d))

    fun inverse(): Affine {
        val det = a * e - b * d
        val ia = e / det
        val ib = -b / det
        val id = -d / det
        val ie = a / det
        return Affine(ia, ib, -(ia * c + ib * f), id, ie, -(id * c + ie * f))
    }
}

/** Canonical 112x112 face crop shared with the lab (ArcFace template). */
object CanonicalFace {
    const val SIZE = 112

    /** Eye, eye, nose, mouth, mouth, in YuNet's order (left to right as seen in the photo). */
    val TEMPLATE = doubleArrayOf(
        38.2946, 51.6963,
        73.5318, 51.5014,
        56.0252, 71.7366,
        41.5493, 92.3655,
        70.7299, 92.2041,
    )

    /**
     * Least-squares similarity transform (rotation, uniform scale, shift) from the face's landmarks
     * to the template, i.e. photo -> canonical. Same closed form as `similarity_transform` in
     * lab/faceflush_lab/align.py.
     */
    fun toCanonical(lm: Landmarks): Affine {
        val src = listOf(lm.eyeA, lm.eyeB, lm.nose, lm.mouthA, lm.mouthB)
        val msx = src.sumOf { it.x.toDouble() } / 5
        val msy = src.sumOf { it.y.toDouble() } / 5
        var mdx = 0.0
        var mdy = 0.0
        for (i in 0 until 5) {
            mdx += TEMPLATE[i * 2]
            mdy += TEMPLATE[i * 2 + 1]
        }
        mdx /= 5
        mdy /= 5
        var norm = 0.0
        var dot = 0.0
        var cross = 0.0
        src.forEachIndexed { i, p ->
            val sx = p.x - msx
            val sy = p.y - msy
            val dx = TEMPLATE[i * 2] - mdx
            val dy = TEMPLATE[i * 2 + 1] - mdy
            norm += sx * sx + sy * sy
            dot += sx * dx + sy * dy
            cross += sx * dy - sy * dx
        }
        val a = dot / norm
        val b = cross / norm
        return Affine(a, -b, mdx - (a * msx - b * msy), b, a, mdy - (b * msx + a * msy))
    }
}

/**
 * A multi-channel float image in canonical face space (cloak pattern or morph field), loaded from
 * the lab's export format: "FFPT", then little-endian int32 version, channels, size, followed by
 * channels * size * size float32 values (channel-major, rows top to bottom).
 */
class CanonicalField(val channels: Int, val size: Int, private val data: FloatArray) {
    init {
        require(data.size == channels * size * size)
    }

    /** Bilinear sample at canonical pixel coordinates (integer = pixel index); 0 outside. */
    fun sample(channel: Int, x: Double, y: Double): Float {
        val x0 = kotlin.math.floor(x).toInt()
        val y0 = kotlin.math.floor(y).toInt()
        val fx = (x - x0).toFloat()
        val fy = (y - y0).toFloat()
        val base = channel * size * size
        fun at(xx: Int, yy: Int) = if (xx < 0 || yy < 0 || xx >= size || yy >= size) 0f else data[base + yy * size + xx]
        val top = at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx
        val bottom = at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx
        return top * (1 - fy) + bottom * fy
    }

    /** Writes the field in the same format [read] expects. */
    fun write(output: java.io.OutputStream) {
        val bb = java.nio.ByteBuffer.allocate(16 + data.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.put("FFPT".toByteArray(Charsets.US_ASCII)).putInt(1).putInt(channels).putInt(size)
        bb.asFloatBuffer().put(data)
        output.write(bb.array())
    }

    companion object {
        fun read(input: java.io.InputStream): CanonicalField {
            val bytes = input.readBytes()
            val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { bb.get(it) }
            require(String(magic, Charsets.US_ASCII) == "FFPT") { "Not a FaceFlush field file" }
            require(bb.int == 1) { "Unsupported field version" }
            val channels = bb.int
            val size = bb.int
            val data = FloatArray(channels * size * size)
            bb.asFloatBuffer().get(data)
            return CanonicalField(channels, size, data)
        }
    }
}
