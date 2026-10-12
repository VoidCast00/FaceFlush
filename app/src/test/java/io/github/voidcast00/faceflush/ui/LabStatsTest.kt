package io.github.voidcast00.faceflush.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabStatsTest {
    @Test
    fun measuredPointsAndInBetween() {
        assertEquals(87, LabStats.recognised(8f, rainbow = false))
        assertEquals(14, LabStats.recognised(16f, rainbow = false))
        assertEquals(3, LabStats.recognised(24f, rainbow = false))
        assertEquals(39, LabStats.recognised(16f, rainbow = true))
        assertEquals(99, LabStats.recognised(0f, rainbow = false))
        val mid = LabStats.recognised(12f, rainbow = false)
        assertTrue(mid in 15..86)
        assertEquals(3, LabStats.recognised(40f, rainbow = false))
    }
}
