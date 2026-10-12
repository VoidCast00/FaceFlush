package io.github.voidcast00.faceflush.io

import java.io.ByteArrayOutputStream

/** Kinds of hidden information a photo can carry, as reported after cleaning. */
enum class Found {
    LOCATION,
    CAMERA,
    DATE,
    /** Descriptions, comments, XMP/IPTC blocks, editing history, PNG text. */
    TEXT,
    /** A preview thumbnail (it can show the uncropped original), extra images, motion-photo video. */
    EXTRA,
    /** Any other camera data (exposure, lens, maker notes…). */
    OTHER,
}

class Cleaned(val bytes: ByteArray, val found: Set<Found>)

/**
 * Removes metadata from JPEG and PNG files without re-encoding, so the picture stays bit-for-bit the
 * same. Only what is needed to show the image correctly is kept: the image data itself, its colour
 * profile and, for JPEG, the rotation (written back as a tiny EXIF block holding nothing else).
 */
object MetadataCleaner {

    /** Null if the bytes are neither JPEG nor PNG. */
    fun clean(bytes: ByteArray): Cleaned? = when {
        isJpeg(bytes) -> cleanJpeg(bytes)
        isPng(bytes) -> cleanPng(bytes)
        else -> null
    }

    fun isJpeg(b: ByteArray) = b.size > 3 && u8(b, 0) == 0xFF && u8(b, 1) == 0xD8

    fun isPng(b: ByteArray) = b.size > 8 && PNG_SIGNATURE.indices.all { b[it] == PNG_SIGNATURE[it] }

    // ---- JPEG ----

    fun cleanJpeg(b: ByteArray): Cleaned {
        val found = mutableSetOf<Found>()
        var orientation = 1
        var app0: ByteArray? = null
        val body = ByteArrayOutputStream(b.size)
        var i = 2
        var ended = false
        while (i + 1 < b.size) {
            if (u8(b, i) != 0xFF) break // not a marker: damaged file, stop here
            val marker = u8(b, i + 1)
            when {
                marker == 0xFF -> { i++; continue } // fill byte
                marker == 0xD9 -> { ended = true; i += 2; break }
                marker in 0xD0..0xD7 || marker == 0x01 -> { body.write(b, i, 2); i += 2; continue }
            }
            if (i + 4 > b.size) break
            val end = minOf(b.size, i + 2 + u16be(b, i + 2))
            val p = i + 4 // payload
            when {
                marker == 0xE0 && startsWith(b, p, "JFIF\u0000") -> if (app0 == null) app0 = b.copyOfRange(i, end)
                marker == 0xE1 && startsWith(b, p, "Exif\u0000\u0000") ->
                    orientation = readExif(b, p + 6, end, found)
                marker == 0xE2 && startsWith(b, p, "ICC_PROFILE\u0000") -> body.write(b, i, end - i)
                marker == 0xE2 && startsWith(b, p, "MPF\u0000") -> found += Found.EXTRA
                // Needed to decode Adobe-made files correctly; holds no personal data
                marker == 0xEE && startsWith(b, p, "Adobe") -> body.write(b, i, end - i)
                marker == 0xED || marker == 0xFE -> found += Found.TEXT // IPTC, comments
                marker == 0xE1 -> found += Found.TEXT // XMP
                marker in 0xE0..0xEF -> found += Found.OTHER
                else -> body.write(b, i, end - i) // tables, frame and scan headers
            }
            i = end
            if (marker == 0xDA) {
                // Compressed image data runs until the next real marker
                var j = i
                while (j + 1 < b.size) {
                    if (u8(b, j) == 0xFF) {
                        val n = u8(b, j + 1)
                        if (n == 0x00 || n in 0xD0..0xD7) { j += 2; continue }
                        break
                    }
                    j++
                }
                if (j + 1 >= b.size) j = b.size
                body.write(b, i, j - i)
                i = j
            }
        }
        // Anything after the end marker: motion-photo video, gain maps, extra pictures
        if (ended && i < b.size) found += Found.EXTRA

        val out = ByteArrayOutputStream(body.size() + 64)
        out.write(0xFF)
        out.write(0xD8)
        app0?.let { out.write(it) }
        if (orientation in 2..8) out.write(orientationExif(orientation))
        body.writeTo(out)
        out.write(0xFF)
        out.write(0xD9)
        return Cleaned(out.toByteArray(), found)
    }

    /** A minimal EXIF block holding only the rotation, so the photo stays the right way up. */
    fun orientationExif(orientation: Int): ByteArray = byteArrayOf(
        0xFF.toByte(), 0xE1.toByte(), 0, 34, // APP1, length 34
        'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0,
        'M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8, // big-endian TIFF, IFD0 at 8
        0, 1, // one entry
        0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0, // Orientation, SHORT, 1 value
        0, 0, 0, 0, // no further IFD
    )

    // ---- PNG ----

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

    /** Chunks needed to show the image (and its colours, transparency, animation) correctly. */
    private val PNG_KEEP = setOf(
        "IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT", "pHYs", "bKGD",
        "acTL", "fcTL", "fdAT",
    )

    fun cleanPng(b: ByteArray): Cleaned {
        val found = mutableSetOf<Found>()
        val out = ByteArrayOutputStream(b.size)
        out.write(b, 0, 8)
        var i = 8
        var ended = false
        while (i + 12 <= b.size) {
            val len = u32be(b, i)
            if (len < 0 || i + 12 + len > b.size) break
            val type = String(b, i + 4, 4, Charsets.US_ASCII)
            val end = i + 12 + len
            if (type in PNG_KEEP) {
                out.write(b, i, end - i)
            } else {
                found += when (type) {
                    "tEXt", "zTXt", "iTXt" -> Found.TEXT
                    "tIME" -> Found.DATE
                    "eXIf" -> { readExif(b, i + 8, i + 8 + len, found); Found.OTHER }
                    else -> Found.OTHER
                }
            }
            i = end
            if (type == "IEND") { ended = true; break }
        }
        if (ended && i < b.size) found += Found.EXTRA
        return Cleaned(out.toByteArray(), found)
    }

    // ---- EXIF reading (only to report what was there and keep the rotation) ----

    /** Reads the EXIF (TIFF) block at [start] and returns its orientation (1 if none). */
    private fun readExif(b: ByteArray, start: Int, end: Int, found: MutableSet<Found>): Int =
        try {
            readTiff(b, start, end, found)
        } catch (e: IndexOutOfBoundsException) {
            found += Found.OTHER
            1
        }

    private fun readTiff(b: ByteArray, start: Int, end: Int, found: MutableSet<Found>): Int {
        if (end - start < 8) return 1
        val le = u8(b, start) == 'I'.code
        fun u16(o: Int) = if (le) u8(b, start + o) or (u8(b, start + o + 1) shl 8) else u16be(b, start + o)
        fun u32(o: Int) = if (le) u16(o) or (u16(o + 2) shl 16) else (u16(o) shl 16) or u16(o + 2)
        fun entries(ifd: Int): List<Pair<Int, Int>> { // tag to entry offset
            if (ifd <= 0 || start + ifd + 2 > end) return emptyList()
            return (0 until u16(ifd)).map { k -> ifd + 2 + 12 * k }.filter { start + it + 12 <= end }.map { u16(it) to it }
        }

        var orientation = 1
        val ifd0 = u32(4)
        found += Found.OTHER
        for ((tag, at) in entries(ifd0)) {
            when (tag) {
                0x0112 -> orientation = u16(at + 8)
                0x010F, 0x0110 -> found += Found.CAMERA
                0x0132 -> found += Found.DATE
                0x010E, 0x013B, 0x8298, 0x9C9B, 0x9C9C, 0x9C9D, 0x9C9E, 0x9C9F -> found += Found.TEXT
                0x8825 -> if (entries(u32(at + 8)).any { it.first in 1..4 }) found += Found.LOCATION
                0x8769 -> if (entries(u32(at + 8)).any { it.first == 0x9003 || it.first == 0x9004 }) found += Found.DATE
            }
        }
        // A second IFD holds the preview thumbnail
        val count = entries(ifd0).size
        if (count > 0 && start + ifd0 + 2 + 12 * count + 4 <= end && u32(ifd0 + 2 + 12 * count) != 0) found += Found.EXTRA
        return orientation
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16be(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun u32be(b: ByteArray, i: Int) = (u16be(b, i) shl 16) or u16be(b, i + 2)
    private fun startsWith(b: ByteArray, at: Int, s: String) =
        at + s.length <= b.size && s.indices.all { b[at + it] == s[it].code.toByte() }
}
