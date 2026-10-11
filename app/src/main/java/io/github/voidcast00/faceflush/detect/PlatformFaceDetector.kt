package io.github.voidcast00.faceflush.detect

import android.graphics.Bitmap
import android.graphics.PointF
import io.github.voidcast00.faceflush.effects.Box
import io.github.voidcast00.faceflush.effects.Landmarks
import io.github.voidcast00.faceflush.effects.Pt
import android.media.FaceDetector as AndroidFaceDetector

/**
 * Android's built-in detector. No extra libraries, but it only finds upright frontal faces and
 * reports just the point between the eyes and the eye distance, so box and landmarks are estimated.
 */
class PlatformFaceDetector : FaceDetector {

    override fun detect(bitmap: Bitmap): List<DetectedFace> {
        // The platform detector needs RGB_565 and an even width
        val evenWidth = bitmap.width and 1.inv()
        val input = Bitmap.createBitmap(evenWidth, bitmap.height, Bitmap.Config.RGB_565)
        android.graphics.Canvas(input).drawBitmap(bitmap, 0f, 0f, null)
        try {
            val found = arrayOfNulls<AndroidFaceDetector.Face>(MAX_FACES)
            val count = AndroidFaceDetector(input.width, input.height, MAX_FACES).findFaces(input, found)
            val mid = PointF()
            return found.take(count).filterNotNull()
                .filter { it.confidence() >= AndroidFaceDetector.Face.CONFIDENCE_THRESHOLD }
                .map { face ->
                    face.getMidPoint(mid)
                    val d = face.eyesDistance()
                    val box = Box(mid.x - 1.25f * d, mid.y - 1.1f * d, mid.x + 1.25f * d, mid.y + 1.9f * d)
                    val landmarks = Landmarks(
                        eyeA = Pt(mid.x - d / 2f, mid.y),
                        eyeB = Pt(mid.x + d / 2f, mid.y),
                        nose = Pt(mid.x, mid.y + 0.6f * d),
                        mouthA = Pt(mid.x - 0.45f * d, mid.y + 1.15f * d),
                        mouthB = Pt(mid.x + 0.45f * d, mid.y + 1.15f * d),
                    )
                    DetectedFace(box, landmarks)
                }
        } finally {
            input.recycle()
        }
    }

    private companion object {
        const val MAX_FACES = 32
    }
}
