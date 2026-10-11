package io.github.voidcast00.faceflush.detect

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import io.github.voidcast00.faceflush.effects.Box
import io.github.voidcast00.faceflush.effects.Landmarks
import io.github.voidcast00.faceflush.effects.Pt
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.FaceDetectorYN
import java.io.File

/** OpenCV's YuNet CNN face detector (MIT-licensed model bundled in assets). */
class YuNetDetector private constructor(private val modelPath: String) : FaceDetector {

    override fun detect(bitmap: Bitmap): List<DetectedFace> {
        val rgba = Mat()
        val bgr = Mat()
        val faces = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            val net = FaceDetectorYN.create(
                modelPath, "", Size(bgr.cols().toDouble(), bgr.rows().toDouble()),
                SCORE_THRESHOLD, NMS_THRESHOLD, TOP_K,
            )
            net.detect(bgr, faces)
            return (0 until faces.rows()).map { row ->
                // Each row: x, y, w, h, then (x, y) for right eye, left eye, nose, right and left mouth corner, then score
                val v = FloatArray(15)
                faces.get(row, 0, v)
                fun pt(i: Int) = Pt(v[4 + i * 2], v[5 + i * 2])
                DetectedFace(
                    Box(v[0], v[1], v[0] + v[2], v[1] + v[3]),
                    Landmarks(pt(0), pt(1), pt(2), pt(3), pt(4)),
                )
            }
        } finally {
            rgba.release()
            bgr.release()
            faces.release()
        }
    }

    companion object {
        private const val TAG = "YuNetDetector"
        private const val MODEL = "face_detection_yunet_2023mar.onnx"
        private const val SCORE_THRESHOLD = 0.6f
        private const val NMS_THRESHOLD = 0.3f
        private const val TOP_K = 5000

        fun createOrNull(context: Context): YuNetDetector? = try {
            if (!OpenCVLoader.initLocal()) {
                Log.w(TAG, "OpenCV native library failed to load")
                null
            } else {
                // OpenCV reads the model from a file path, so copy it out of the APK once
                val file = File(context.filesDir, MODEL)
                if (!file.exists()) {
                    context.assets.open(MODEL).use { input -> file.outputStream().use { input.copyTo(it) } }
                }
                YuNetDetector(file.absolutePath)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "YuNet unavailable", e)
            null
        }
    }
}
