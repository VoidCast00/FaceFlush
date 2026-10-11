package io.github.voidcast00.faceflush.io

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import kotlin.math.max

data class LoadedImage(val bitmap: Bitmap, val isPng: Boolean)

object ImageLoader {
    /** Longest side we keep in memory; bigger photos are downsampled to avoid running out of memory. */
    private const val MAX_SIDE = 4096

    /**
     * Decodes [uri] into a mutable ARGB_8888 bitmap. ImageDecoder applies the EXIF orientation, so
     * the pixels are upright and the metadata itself can be dropped on export.
     */
    fun load(context: Context, uri: Uri): LoadedImage {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val longest = max(info.size.width, info.size.height)
            if (longest > MAX_SIDE) {
                val s = MAX_SIDE.toFloat() / longest
                decoder.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
            }
        }
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else {
            bitmap.copy(Bitmap.Config.ARGB_8888, true).also { bitmap.recycle() }
        }
        val isPng = context.contentResolver.getType(uri) == "image/png"
        return LoadedImage(argb, isPng)
    }
}
