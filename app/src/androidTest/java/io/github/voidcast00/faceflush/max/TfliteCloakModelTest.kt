package io.github.voidcast00.faceflush.max

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.voidcast00.faceflush.effects.CanonicalFace
import io.github.voidcast00.faceflush.effects.CloakTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/** Loads the real engine on the phone, checks its outputs make sense and times one step. */
class TfliteCloakModelTest {

    @Test
    fun stepRunsAndIsConsistent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val n = CanonicalFace.SIZE
        val rng = Random(0)
        val crop = FloatArray(n * n * 3) { rng.nextFloat() }
        val pattern = FloatArray(n * n * 3) { rng.nextFloat() * 2 - 1 }
        val weight = FloatArray(n * n) { 1f }

        val t0 = System.nanoTime()
        TfliteCloakModel(context).use { model ->
            val load = (System.nanoTime() - t0) / 1e6
            val clean = model.step(crop, pattern, weight, 0f, listOf(FloatArray(128)), CloakTransform.IDENTITY)
            val ref = clean.embeddings[0]
            assertEquals(1f, sqrt(ref.sumOf { (it * it).toDouble() }).toFloat(), 1e-3f)

            // With eps 0 and no transform, the face is compared with itself
            val self = model.step(crop, pattern, weight, 0f, listOf(ref), CloakTransform.IDENTITY)
            assertEquals(1f, self.loss, 1e-3f)

            val times = (0 until 5).map {
                val s = System.nanoTime()
                val r = model.step(crop, pattern, weight, 8f / 255f, listOf(ref), CloakTransform.random(rng))
                assertTrue(r.grad.any { g -> g != 0f })
                (System.nanoTime() - s) / 1e6
            }
            Log.i("FaceFlushBench", "load %.0f ms, step ms: %s".format(load, times.joinToString { "%.0f".format(it) }))
        }
    }
}
