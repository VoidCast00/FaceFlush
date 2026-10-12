package io.github.voidcast00.faceflush.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaxCloakTest {

    private val lm = Landmarks(
        Pt(271.6f, 269.4f), Pt(328.5f, 276.1f), Pt(309.7f, 312.9f), Pt(270.9f, 342.2f), Pt(311.3f, 348.4f),
    )

    private fun image() = PixelBuffer(600, 600, IntArray(600 * 600) { i -> argb(i % 600 % 256, i / 600 % 256, 128) })

    /** Gradient that points away from a fixed target, so descent drives the pattern to the target. */
    private class FakeModel(private val target: FloatArray) : CloakModel {
        var calls = 0
        val epsSeen = mutableListOf<Float>()
        override val embeddingSizes = listOf(4)

        override fun step(
            crop: FloatArray, pattern: FloatArray, weight: FloatArray, eps: Float, refs: List<FloatArray>,
            transform: CloakTransform,
        ): CloakModel.Result {
            calls++
            epsSeen += eps
            val grad = FloatArray(pattern.size) { pattern[it] - target[it] }
            return CloakModel.Result(0f, grad, listOf(FloatArray(4) { 1f }))
        }
    }

    @Test
    fun descendsTowardsTheModelsGoalWithinBounds() {
        val n = CanonicalFace.SIZE
        val target = FloatArray(n * n * 3) { if (it % 3 == 0) 0.8f else -0.5f } // NHWC: red +0.8, green/blue -0.5
        val model = FakeModel(target)
        val field = MaxCloak(model).optimise(image(), lm, 8f / 255f, steps = 40, start = null, seed = 1, eot = 2)

        assertEquals(81, model.calls) // reference embeddings + 40 steps x 2 transforms
        assertEquals(0f, model.epsSeen.first()) // references come from the clean face
        // Brightness only: all channels move together, towards the average goal (0.8 - 0.5 - 0.5) / 3.
        // Fixed-size signed steps (10 / 40 = 0.25) settle within one step of it
        val goal = (0.8f - 0.5f - 0.5f) / 3
        for (c in 0 until 3) assertEquals(goal, field.sample(c, 50.0, 50.0), 0.26f)
        assertEquals(field.sample(0, 50.0, 50.0), field.sample(1, 50.0, 50.0), 0f)
        assertEquals(field.sample(0, 50.0, 50.0), field.sample(2, 50.0, 50.0), 0f)
    }

    @Test
    fun rainbowStyleMovesEachChannelOnItsOwn() {
        val n = CanonicalFace.SIZE
        val target = FloatArray(n * n * 3) { if (it % 3 == 0) 0.8f else -0.5f }
        val field = MaxCloak(FakeModel(target)).optimise(
            image(), lm, 8f / 255f, steps = 40, start = null, seed = 1, brightnessOnly = false,
        )
        assertEquals(0.8f, field.sample(0, 50.0, 50.0), 0.26f)
        assertEquals(-0.5f, field.sample(1, 50.0, 50.0), 0.26f)
        assertEquals(-0.5f, field.sample(2, 50.0, 50.0), 0.26f)
    }

    @Test
    fun warmStartUsesTheQuickCloaksBrightness() {
        val n = CanonicalFace.SIZE
        val start = CanonicalField(3, n, FloatArray(3 * n * n) { if (it < n * n) 0.9f else 0f }) // red only
        val model = FakeModel(FloatArray(n * n * 3))
        val field = MaxCloak(model).optimise(image(), lm, 8f / 255f, steps = 0, start = start, seed = 1)
        for (c in 0 until 3) assertEquals(0.3f, field.sample(c, 50.0, 50.0), 1e-5f)
    }

    @Test
    fun stopsWhenCancelled() {
        val model = FakeModel(FloatArray(CanonicalFace.SIZE * CanonicalFace.SIZE * 3))
        var steps = 0
        MaxCloak(model).optimise(image(), lm, 0.03f, steps = 100, start = null, seed = 1,
            onStep = { steps = it }, isCancelled = { steps >= 5 })
        assertEquals(5, steps)
    }

    @Test
    fun cropAndWeightsLookSane() {
        val crop = MaxCloak.alignedCrop(image(), lm)
        assertEquals(CanonicalFace.SIZE * CanonicalFace.SIZE * 3, crop.size)
        assertTrue(crop.all { it in 0f..1f })
        val w = MaxCloak.cropWeight(crop)
        assertEquals(0f, w[0], 0.06f) // corner is faded out by the edge mask
        assertTrue(w[56 * 112 + 56] > 0.3f)
    }
}
