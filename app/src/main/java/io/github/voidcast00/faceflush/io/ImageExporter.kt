package io.github.voidcast00.faceflush.io

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes results as brand-new files. Encoding straight from a Bitmap writes no EXIF, so GPS,
 * camera model and timestamps from the original never reach the output.
 */
object ImageExporter {
    private const val ALBUM = "FaceFlush"
    private const val SHARE_DIR = "shared"
    private const val JPEG_QUALITY = 95

    fun mimeType(png: Boolean) = if (png) "image/png" else "image/jpeg"

    private fun fileName(png: Boolean): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "FaceFlush_$stamp.${if (png) "png" else "jpg"}"
    }

    private fun Bitmap.writeTo(out: OutputStream, png: Boolean) {
        val ok = if (png) compress(Bitmap.CompressFormat.PNG, 100, out)
        else compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (!ok) throw IOException("Could not encode image")
    }

    /** Saves to Pictures/FaceFlush. Needs no storage permission on Android 10+. */
    fun saveToGallery(context: Context, bitmap: Bitmap, png: Boolean): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName(png))
            put(MediaStore.Images.Media.MIME_TYPE, mimeType(png))
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("Could not create gallery entry")
        try {
            resolver.openOutputStream(uri)?.use { bitmap.writeTo(it, png) }
                ?: throw IOException("Could not open gallery entry")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** Writes a temporary copy for the share sheet and returns a content:// URI for it. */
    fun writeForSharing(context: Context, bitmap: Bitmap, png: Boolean): Uri {
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        val file = File(dir, fileName(png))
        file.outputStream().use { bitmap.writeTo(it, png) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun clearShareCache(context: Context) {
        File(context.cacheDir, SHARE_DIR).deleteRecursively()
    }
}
