package io.github.voidcast00.faceflush.max

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.voidcast00.faceflush.MainActivity
import io.github.voidcast00.faceflush.R
import io.github.voidcast00.faceflush.effects.CanonicalField
import io.github.voidcast00.faceflush.effects.Landmarks
import io.github.voidcast00.faceflush.effects.MaxCloak
import io.github.voidcast00.faceflush.effects.PixelBuffer
import io.github.voidcast00.faceflush.effects.Pt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.roundToInt

/**
 * "Max privacy": optimises a cloak for each selected face on the phone, as a foreground job with a
 * progress notification. Input: the photo (saved by [writeSource]) and one set of landmarks per
 * face; output: one canonical field per face in [resultFile].
 */
class MaxPrivacyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val notifications = context.getSystemService(NotificationManager::class.java)

    override suspend fun getForegroundInfo() = foregroundInfo(0, null)

    override suspend fun doWork(): Result {
        val lms = inputData.getFloatArray(KEY_LANDMARKS) ?: return Result.failure()
        val eps = inputData.getFloat(KEY_EPS, 8f / 255f)
        val seed = inputData.getLong(KEY_SEED, 0L)
        val faceCount = lms.size / 10
        val total = faceCount * STEPS
        createChannel(applicationContext)
        setForeground(foregroundInfo(0, null))

        val ok = withContext(Dispatchers.Default) {
            val buf = readSource(applicationContext) ?: return@withContext false
            val start = try {
                applicationContext.assets.open("cloak_pattern.bin").use(CanonicalField::read)
            } catch (e: java.io.IOException) {
                null
            }
            val t0 = System.nanoTime()
            var lastShown = 0L
            TfliteCloakModel(applicationContext).use { model ->
                for (f in 0 until faceCount) {
                    val o = f * 10
                    val lm = Landmarks(
                        Pt(lms[o], lms[o + 1]), Pt(lms[o + 2], lms[o + 3]), Pt(lms[o + 4], lms[o + 5]),
                        Pt(lms[o + 6], lms[o + 7]), Pt(lms[o + 8], lms[o + 9]),
                    )
                    val field = MaxCloak(model).optimise(
                        buf, lm, eps, STEPS, start, seed + f, eot = EOT,
                        onStep = { step ->
                            val done = f * STEPS + step
                            val now = System.nanoTime()
                            if (now - lastShown > 1_000_000_000L || done == total) {
                                lastShown = now
                                val perStep = (now - t0) / 1e9 / done
                                val left = ((total - done) * perStep).roundToInt()
                                val percent = done * 100 / total
                                setProgressAsync(workDataOf(KEY_PROGRESS to percent, KEY_SECONDS_LEFT to left))
                                notifications.notify(NOTIFICATION_RUNNING, notification(percent, left))
                            }
                        },
                        isCancelled = { isStopped },
                    )
                    if (isStopped) return@withContext false
                    resultFile(applicationContext, f).outputStream().use(field::write)
                }
            }
            true
        }
        if (!ok) return Result.failure()
        notifyDone()
        return Result.success(workDataOf(KEY_FACES to faceCount, KEY_EPS to eps))
    }

    private fun foregroundInfo(percent: Int, secondsLeft: Int?): ForegroundInfo {
        val n = notification(percent, secondsLeft)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_RUNNING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_RUNNING, n)
        }
    }

    private fun notification(percent: Int, secondsLeft: Int?) = applicationContext.let { ctx ->
        val text = if (secondsLeft == null) ctx.getString(R.string.max_notification_starting)
        else ctx.getString(R.string.max_notification_left, (secondsLeft + 59) / 60)
        NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.max_notification_title))
            .setContentText(text)
            .setProgress(100, percent, secondsLeft == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(ctx))
            .addAction(0, ctx.getString(R.string.cancel), WorkManager.getInstance(ctx).createCancelPendingIntent(id))
            .build()
    }

    private fun notifyDone() {
        val ctx = applicationContext
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.max_notification_done))
            .setContentText(ctx.getString(R.string.max_notification_done_text))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        try {
            notifications.notify(NOTIFICATION_DONE, n)
        } catch (e: SecurityException) {
            Log.i(TAG, "Notifications not allowed", e)
        }
    }

    companion object {
        private const val TAG = "MaxPrivacyWorker"
        const val WORK_NAME = "max_privacy"
        const val KEY_LANDMARKS = "landmarks"
        const val KEY_EPS = "eps"
        const val KEY_SEED = "seed"
        const val KEY_FACES = "faces"
        const val KEY_PROGRESS = "progress"
        const val KEY_SECONDS_LEFT = "seconds_left"

        // Same as lab/sim_max.py: 150 signed steps, each averaged over 8 random transforms
        const val STEPS = 150
        const val EOT = 8

        /** Rough time per model call on a mid-range phone, for the estimate shown before starting. */
        const val SECONDS_PER_CALL = 0.25

        private const val CHANNEL = "max_privacy"
        private const val NOTIFICATION_RUNNING = 1
        private const val NOTIFICATION_DONE = 2

        fun estimateMinutes(faces: Int) = ((faces * STEPS * EOT * SECONDS_PER_CALL) / 60).roundToInt().coerceAtLeast(1)

        fun inputData(landmarks: List<Landmarks>, eps: Float, seed: Long): Data {
            val flat = landmarks.flatMap { lm ->
                listOf(lm.eyeA, lm.eyeB, lm.nose, lm.mouthA, lm.mouthB).flatMap { listOf(it.x, it.y) }
            }
            return workDataOf(KEY_LANDMARKS to flat.toFloatArray(), KEY_EPS to eps, KEY_SEED to seed)
        }

        private fun dir(context: Context) = File(context.filesDir, "max").apply { mkdirs() }
        fun resultFile(context: Context, face: Int) = File(dir(context), "face_$face.bin")
        private fun sourceFile(context: Context) = File(dir(context), "source.bin")

        /** Saves the pixels the job works on (and clears old results). */
        fun writeSource(context: Context, buf: PixelBuffer) {
            dir(context).listFiles()?.forEach { it.delete() }
            DataOutputStream(sourceFile(context).outputStream().buffered()).use { out ->
                out.writeInt(buf.width)
                out.writeInt(buf.height)
                val bytes = java.nio.ByteBuffer.allocate(buf.pixels.size * 4)
                bytes.asIntBuffer().put(buf.pixels)
                out.write(bytes.array())
            }
        }

        private fun readSource(context: Context): PixelBuffer? {
            val file = sourceFile(context)
            if (!file.exists()) return null
            return DataInputStream(file.inputStream().buffered()).use { input ->
                val w = input.readInt()
                val h = input.readInt()
                val bytes = ByteArray(w * h * 4).also { input.readFully(it) }
                val pixels = IntArray(w * h)
                java.nio.ByteBuffer.wrap(bytes).asIntBuffer().get(pixels)
                PixelBuffer(w, h, pixels)
            }
        }

        fun readResults(context: Context, faces: Int): List<CanonicalField>? = try {
            (0 until faces).map { f -> resultFile(context, f).inputStream().use(CanonicalField::read) }
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Missing Max privacy result", e)
            null
        }

        fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL, context.getString(R.string.max_channel), NotificationManager.IMPORTANCE_LOW,
            )
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
