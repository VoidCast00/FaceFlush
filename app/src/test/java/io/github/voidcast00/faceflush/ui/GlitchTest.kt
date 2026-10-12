package io.github.voidcast00.faceflush.ui

import io.github.voidcast00.faceflush.effects.Technique
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GlitchTest {

    private val grid = AsciiGrid(File("src/main/res/raw/ascii_portrait.txt").readLines())

    @Test
    fun faceSitsAroundTheEyeBand() {
        assertTrue(grid.band.isNotEmpty())
        assertTrue(grid.face.size > 200)
        assertTrue(grid.band.count { (r, c) -> grid.inFace(r, c) } > grid.band.size / 2)
        assertTrue(grid.faceRows.first >= 0 && grid.faceRows.last < grid.rows)
        assertTrue(grid.faceCols.first >= 0 && grid.faceCols.last < grid.columns)
    }

    @Test
    fun progressSpreadsFromTheBandToTheWholeFace() {
        fun covered(fraction: Float) = GlitchStyle.Progress(fraction).cells(grid, 3).map { it.row to it.col }.toSet()
        // At the start only the eye band (and the face cells on its middle row) glitch
        assertTrue(covered(0f).containsAll(grid.band))
        assertTrue(covered(0f).all { it in grid.band || it.first.toFloat() == grid.eyeRow })
        assertTrue(covered(0.5f).size > covered(0.1f).size)
        assertTrue(covered(1f).containsAll(grid.face))
    }

    @Test
    fun rainbowProgressHasColours() {
        assertTrue(GlitchStyle.Progress(1f, rainbow = true).cells(grid, 1).all { it.hue != null })
        assertTrue(GlitchStyle.Progress(1f).cells(grid, 1).all { it.hue == null })
    }

    @Test
    fun scanLineMoves() {
        fun rows(tick: Int) = GlitchStyle.Scan.cells(grid, tick).filter { it.alpha == 1f }.map { it.row }.toSet()
        assertEquals(1, rows(5).size)
        assertTrue(rows(5) != rows(9))
    }

    @Test
    fun everyTechniqueHidesTheFaceItsOwnWay() {
        val drawn = Technique.visible.associateWith { GlitchStyle.Hidden(it).cells(grid, 0) }
        drawn.values.forEach { cells -> assertTrue(cells.isNotEmpty()); assertTrue(cells.all { it.alpha in 0f..1f }) }
        assertTrue(drawn.getValue(Technique.MASK).all { it.alpha == 1f })
        assertEquals(grid.face.size, drawn.getValue(Technique.MASK).size)
        assertTrue(drawn.getValue(Technique.LANDMARKS).size < grid.face.size / 2)
    }
}
