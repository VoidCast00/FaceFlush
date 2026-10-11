package io.github.voidcast00.faceflush.detect

import android.content.Context
import android.graphics.Bitmap
import io.github.voidcast00.faceflush.effects.Box
import io.github.voidcast00.faceflush.effects.Landmarks
import kotlin.math.max

data class DetectedFace(val box: Box, val landmarks: Landmarks?)

interface FaceDetector {
    /** Finds faces in [bitmap]; results are in [bitmap]'s pixel coordinates. */
    fun detect(bitmap: Bitmap): List<DetectedFace>
}

enum class DetectorKind { YUNET, PLATFORM }

object FaceDetectors {
    /** Creates the requested detector, or falls back to the platform one if OpenCV can't load. */
    fun create(context: Context, kind: DetectorKind): Pair<FaceDetector, DetectorKind> {
        if (kind == DetectorKind.YUNET) {
            YuNetDetector.createOrNull(context)?.let { return it to DetectorKind.YUNET }
        }
        return PlatformFaceDetector() to DetectorKind.PLATFORM
    }
}

/** Detects on a copy no larger than [maxSide] px and maps the results back to [bitmap]'s size. */
internal fun FaceDetector.detectDownscaled(bitmap: Bitmap, maxSide: Int): List<DetectedFace> {
    val longest = max(bitmap.width, bitmap.height)
    if (longest <= maxSide) return detect(bitmap)
    val s = maxSide.toFloat() / longest
    val small = Bitmap.createScaledBitmap(bitmap, (bitmap.width * s).toInt(), (bitmap.height * s).toInt(), true)
    try {
        val inv = 1f / s
        return detect(small).map { DetectedFace(it.box.scaled(inv), it.landmarks?.scaled(inv)) }
    } finally {
        small.recycle()
    }
}
