package io.github.voidcast00.faceflush.io

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MetadataCleanerTest {

    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    private fun ByteArray.has(text: String) = String(this, Charsets.ISO_8859_1).contains(text)

    private fun ByteArray.contains(part: ByteArray) = String(this, Charsets.ISO_8859_1).contains(String(part, Charsets.ISO_8859_1))

    /** The compressed picture of a JPEG: from the scan header to the end marker. */
    private fun jpegScan(b: ByteArray): ByteArray {
        val s = String(b, Charsets.ISO_8859_1)
        val start = s.indexOf("\u00FF\u00DA")
        return b.copyOfRange(start, s.indexOf("\u00FF\u00D9", start) + 2)
    }

    /** All image data chunks of a PNG, in order. */
    private fun pngData(b: ByteArray): ByteArray {
        val s = String(b, Charsets.ISO_8859_1)
        val start = s.indexOf("IDAT") - 4
        return b.copyOfRange(start, s.indexOf("IEND") - 4)
    }

    @Test
    fun jpegLosesEverythingButThePicture() {
        val original = resource("metadata_test.jpg")
        listOf("TestMake", "secret comment", "secret xmp", "ftypmp42").forEach { assertTrue(it, original.has(it)) }

        val cleaned = MetadataCleaner.clean(original)
        assertNotNull(cleaned)
        val bytes = cleaned!!.bytes
        listOf("TestMake", "TestModel", "2026:10:12", "secret", "xmpmeta", "ftypmp42").forEach { assertFalse(it, bytes.has(it)) }
        assertEquals(
            setOf(Found.LOCATION, Found.CAMERA, Found.DATE, Found.TEXT, Found.EXTRA, Found.OTHER),
            cleaned.found,
        )
        // The compressed picture is copied untouched, so the pixels are exactly the same
        assertTrue(bytes.contains(jpegScan(original)))
        assertTrue(bytes.contains(MetadataCleaner.orientationExif(6)))
        File("build/metadata_clean_test.jpg").writeBytes(bytes)
    }

    @Test
    fun cleaningTwiceChangesNothingAndFindsNothing() {
        val once = MetadataCleaner.clean(resource("metadata_test.jpg"))!!.bytes
        val twice = MetadataCleaner.clean(once)!!
        assertArrayEquals(once, twice.bytes)
        assertEquals(setOf(Found.OTHER), twice.found) // only the rotation-only EXIF block
    }

    @Test
    fun pngLosesTextChunks() {
        val original = resource("metadata_test.png")
        assertTrue(original.has("secret author"))
        val cleaned = MetadataCleaner.clean(original)!!
        assertFalse(cleaned.bytes.has("secret"))
        assertEquals(setOf(Found.TEXT), cleaned.found)
        assertTrue(cleaned.bytes.contains(pngData(original)))
        File("build/metadata_clean_test.png").writeBytes(cleaned.bytes)
    }

    @Test
    fun otherFormatsAreLeftToTheCaller() {
        assertNull(MetadataCleaner.clean("RIFF....WEBPVP8 ".toByteArray()))
    }
}
