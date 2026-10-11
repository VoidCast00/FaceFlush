package io.github.voidcast00.faceflush.max

import android.content.Context
import io.github.voidcast00.faceflush.effects.CanonicalFace
import io.github.voidcast00.faceflush.effects.CloakModel
import io.github.voidcast00.faceflush.effects.CloakTransform
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Runs max_cloak.tflite (built by lab/export_max.py): SFace plus the gradient of its similarity to
 * the clean face. TensorFlow ops inside the gradient run on the Flex delegate, which TFLite picks up
 * automatically because tensorflow-lite-select-tf-ops is on the classpath.
 */
class TfliteCloakModel(context: Context, threads: Int = 4) : CloakModel, Closeable {

    private val interpreter = Interpreter(loadModel(context), Interpreter.Options().setNumThreads(threads))

    override val embeddingSizes = listOf(EMBEDDING)

    private val n = CanonicalFace.SIZE
    private val x = floats(n * n * 3)
    private val pattern = floats(n * n * 3)
    private val weight = floats(n * n)
    private val eps = floats(1)
    private val ref = floats(EMBEDDING)
    private val angle = floats(1)
    private val scale = floats(1)
    private val shift = floats(2)
    private val down = floats(1)
    private val loss = floats(1)
    private val grad = floats(n * n * 3)
    private val embedding = floats(EMBEDDING)

    override fun step(
        crop: FloatArray, pattern: FloatArray, weight: FloatArray, eps: Float, refs: List<FloatArray>,
        transform: CloakTransform,
    ): CloakModel.Result {
        put(x, crop)
        put(this.pattern, pattern)
        put(this.weight, weight)
        put(this.eps, floatArrayOf(eps))
        put(ref, refs[0])
        put(angle, floatArrayOf(transform.angle))
        put(scale, floatArrayOf(transform.scale))
        put(shift, floatArrayOf(transform.shiftX, transform.shiftY))
        put(down, floatArrayOf(transform.down))
        listOf(loss, grad, embedding).forEach { it.rewind() }

        interpreter.runSignature(
            mapOf(
                "x" to x, "pattern" to this.pattern, "weight" to this.weight, "eps" to this.eps,
                "ref_sface" to ref, "angle" to angle, "scale" to scale, "shift" to shift, "down" to down,
            ),
            mapOf("loss" to loss, "grad" to grad, "sface" to embedding),
            "step",
        )
        return CloakModel.Result(get(loss)[0], get(grad), listOf(get(embedding)))
    }

    override fun close() = interpreter.close()

    companion object {
        const val ASSET = "max_cloak.tflite"
        private const val EMBEDDING = 128

        private fun loadModel(context: Context): MappedByteBuffer =
            context.assets.openFd(ASSET).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use {
                    it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }

        private fun floats(count: Int): ByteBuffer = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())

        private fun put(buf: ByteBuffer, values: FloatArray) {
            buf.rewind()
            buf.asFloatBuffer().put(values)
        }

        private fun get(buf: ByteBuffer): FloatArray {
            buf.rewind()
            return FloatArray(buf.capacity() / 4).also { buf.asFloatBuffer().get(it) }
        }
    }
}
