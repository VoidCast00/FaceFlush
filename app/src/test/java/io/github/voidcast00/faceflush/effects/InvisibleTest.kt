package io.github.voidcast00.faceflush.effects

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class InvisibleTest {

    // YuNet landmarks of the sample face used in the lab
    private val lm = Landmarks(
        Pt(271.6f, 269.4f), Pt(328.5f, 276.1f), Pt(309.7f, 312.9f), Pt(270.9f, 342.2f), Pt(311.3f, 348.4f),
    )

    @Test
    fun alignmentMatchesLabReference() {
        // Computed in the lab with SVD-based Umeyama (independent of the closed form in Align.kt)
        val m = CanonicalFace.toCanonical(lm)
        val expected = doubleArrayOf(0.568069, 0.07510086, -136.7519, -0.07510086, 0.568069, -81.67691)
        val actual = doubleArrayOf(m.a, m.b, m.c, m.d, m.e, m.f)
        for (i in expected.indices) assertEquals("element $i", expected[i], actual[i], 1e-3)
    }

    @Test
    fun inverseRoundTrips() {
        val m = CanonicalFace.toCanonical(lm)
        val inv = m.inverse()
        val x = m.mapX(300.0, 310.0)
        val y = m.mapY(300.0, 310.0)
        assertEquals(300.0, inv.mapX(x, y), 1e-9)
        assertEquals(310.0, inv.mapY(x, y), 1e-9)
    }

    @Test
    fun fieldFileRoundTrips() {
        val values = FloatArray(2 * 4 * 4) { it * 0.5f }
        val bb = ByteBuffer.allocate(16 + values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("FFPT".toByteArray()).putInt(1).putInt(2).putInt(4)
        values.forEach { bb.putFloat(it) }
        val field = CanonicalField.read(ByteArrayInputStream(bb.array()))
        assertEquals(2, field.channels)
        assertEquals(4, field.size)
        assertEquals(values[16 + 5], field.sample(1, 1.0, 1.0), 0f)
        assertEquals((values[0] + values[1]) / 2, field.sample(0, 0.5, 0.0), 1e-6f)
    }

    @Test
    fun bundledFieldsParse() {
        // Unit tests run from the app module directory
        val expected = mapOf("cloak_pattern.bin" to 3, "morph_field.bin" to 2)
        for ((name, channels) in expected) {
            val file = java.io.File("src/main/assets/$name")
            if (!file.exists()) continue
            val field = file.inputStream().use { CanonicalField.read(it) }
            assertEquals(name, channels, field.channels)
            assertEquals(name, CanonicalFace.SIZE, field.size)
            for (c in 0 until channels) for (y in 0 until field.size) for (x in 0 until field.size) {
                assertTrue(name, field.sample(c, x.toDouble(), y.toDouble()).isFinite())
            }
        }
    }

    private fun image(): PixelBuffer {
        val w = 600
        val h = 600
        return PixelBuffer(w, h, IntArray(w * h) { i -> argb((i % w * 3) % 256, (i / w * 5) % 256, ((i % w) xor (i / w)) % 256) })
    }

    private fun constantField(channels: Int, value: Float) =
        CanonicalField(channels, 112, FloatArray(channels * 112 * 112) { value })

    @Test
    fun zeroStrengthChangesNothing() {
        val buf = image()
        val original = buf.pixels.copyOf()
        cloak(buf, lm, constantField(3, 1f), 0f)
        morph(buf, lm, constantField(2, 3f), 0f)
        assertArrayEquals(original, buf.pixels)
    }

    @Test
    fun cloakOnlyTouchesTheFace() {
        val buf = image()
        val original = buf.pixels.copyOf()
        cloak(buf, lm, constantField(3, 1f), 8f / 255f)
        val changed = original.indices.filter { original[it] != buf.pixels[it] }
        assertTrue(changed.isNotEmpty())
        // Canonical square maps to roughly x 220..420, y 150..380 for this face
        assertTrue(changed.all { it % 600 in 180..460 && it / 600 in 110..420 })
    }

    @Test
    fun morphMovesContentAroundTheFaceOnly() {
        val buf = image()
        val original = buf.pixels.copyOf()
        morph(buf, lm, constantField(2, 2f), 1f)
        val changed = original.indices.filter { original[it] != buf.pixels[it] }
        assertTrue(changed.isNotEmpty())
        assertTrue(changed.all { it % 600 in 180..460 && it / 600 in 110..420 })
    }

    @Test
    fun invisibleTechniqueRendersThroughEffectRenderer() {
        val buf = image()
        val original = buf.pixels.copyOf()
        val assets = InvisibleAssets(constantField(3, 1f), constantField(2, 2f))
        val region = Region(Box(250f, 230f, 350f, 380f), lm)
        EffectRenderer.render(
            buf, Technique.INVISIBLE, Technique.INVISIBLE.paramsFor(Strength.MEDIUM), listOf(region), 1f, 0L, assets,
        )
        assertTrue(!original.contentEquals(buf.pixels))
    }
}
