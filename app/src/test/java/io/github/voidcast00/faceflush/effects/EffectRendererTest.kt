package io.github.voidcast00.faceflush.effects

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectRendererTest {

    private val size = 200

    /** A gradient with detail everywhere, so any effect visibly changes it. */
    private fun testImage(): PixelBuffer {
        val px = IntArray(size * size) { i ->
            val x = i % size
            val y = i / size
            argb((x * 7 + y) % 256, (y * 5) % 256, ((x xor y) * 3) % 256)
        }
        return PixelBuffer(size, size, px)
    }

    private val face = Region(Box(60f, 60f, 140f, 140f))

    // Stand-ins for the lab-trained cloak pattern and morph field
    private val invisible = InvisibleAssets(
        CanonicalField(3, 112, FloatArray(3 * 112 * 112) { if (it % 2 == 0) 1f else -1f }),
        CanonicalField(2, 112, FloatArray(2 * 112 * 112) { 2f }),
    )

    private fun render(technique: Technique, strength: Strength = Strength.MEDIUM, seed: Long = 42L): IntArray {
        val buf = testImage()
        EffectRenderer.render(buf, technique, technique.paramsFor(strength), listOf(face), 1f, seed, invisible)
        return buf.pixels
    }

    private fun changedCount(before: IntArray, after: IntArray, inside: Boolean): Int {
        var n = 0
        for (i in before.indices) {
            val x = i % size
            val y = i / size
            // Coverage expands the face box, so only judge clearly-inside and clearly-outside pixels
            val isInside = x in 90..110 && y in 90..110
            val isOutside = x < 20 || x > 180 || y < 10 || y > 190
            if ((inside && isInside || !inside && isOutside) && before[i] != after[i]) n++
        }
        return n
    }

    @Test
    fun everyTechniqueChangesTheFaceAndNothingFarAway() {
        val original = testImage().pixels
        for (technique in Technique.entries) {
            val result = render(technique)
            assertTrue("$technique left the face untouched", changedCount(original, result, inside = true) > 0)
            assertEquals("$technique leaked outside the face", 0, changedCount(original, result, inside = false))
        }
    }

    @Test
    fun sameSeedGivesSameResult() {
        for (technique in Technique.entries) {
            assertArrayEquals("$technique", render(technique, seed = 7L), render(technique, seed = 7L))
        }
    }

    @Test
    fun differentSeedChangesRandomEffects() {
        assertFalse(render(Technique.DEAD_PIXELS, seed = 1L).contentEquals(render(Technique.DEAD_PIXELS, seed = 2L)))
    }

    @Test
    fun strongerPresetsChangeMorePixels() {
        val original = testImage().pixels
        for (technique in listOf(Technique.DEAD_PIXELS, Technique.LANDMARKS, Technique.MASK)) {
            val counts = Strength.entries.map { s ->
                val result = render(technique, s)
                result.indices.count { i -> result[i] != original[i] }
            }
            assertTrue("$technique: $counts", counts[0] < counts[1] && counts[1] < counts[2])
        }
    }

    @Test
    fun wholeImageModeCoversCorners() {
        val buf = testImage()
        val original = buf.pixels.copyOf()
        val whole = Region(Box(0f, 0f, size.toFloat(), size.toFloat()), wholeImage = true)
        EffectRenderer.render(buf, Technique.PIXELATE, Technique.PIXELATE.paramsFor(Strength.STRONG), listOf(whole), 1f, 0L)
        assertTrue(buf.pixels[0] != original[0] || buf.pixels[1] != original[1])
        assertTrue(buf.pixels.last() != original.last() || buf.pixels[size * size - 2] != original[size * size - 2])
    }

    @Test
    fun regionOutsideImageIsIgnored() {
        val buf = testImage()
        val original = buf.pixels.copyOf()
        val offscreen = Region(Box(500f, 500f, 600f, 600f))
        for (technique in Technique.entries) {
            EffectRenderer.render(buf, technique, technique.paramsFor(Strength.STRONG), listOf(offscreen), 1f, 0L, invisible)
        }
        assertArrayEquals(original, buf.pixels)
    }

    @Test
    fun choicesSurviveStrengthChange() {
        val params = Technique.MASK.paramsFor(Strength.LIGHT) + (Params.COLOR to 2f)
        assertEquals(2f, Technique.MASK.paramsFor(Strength.STRONG, params)[Params.COLOR])
    }
}
