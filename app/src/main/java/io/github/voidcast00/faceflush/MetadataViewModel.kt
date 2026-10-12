package io.github.voidcast00.faceflush

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.voidcast00.faceflush.io.Found
import io.github.voidcast00.faceflush.io.ImageExporter
import io.github.voidcast00.faceflush.io.ImageLoader
import io.github.voidcast00.faceflush.io.MetadataCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Progress and outcome of "Remove metadata only". */
sealed interface CleanState {
    data object Idle : CleanState
    data class Working(val done: Int, val total: Int) : CleanState
    /** [converted]: photos that were not JPEG or PNG and were re-saved as JPEG from their pixels. */
    data class Done(val saved: Int, val failed: Int, val converted: Int, val found: Set<Found>) : CleanState
}

/**
 * Saves metadata-free copies of photos. JPEG and PNG are cleaned without re-encoding (the picture
 * stays bit-for-bit the same); other formats are decoded and saved as a new JPEG, which carries no
 * metadata at all. The originals are never touched.
 */
class MetadataViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<CleanState>(CleanState.Idle)
    val state: StateFlow<CleanState> = _state.asStateFlow()

    fun clean(uris: List<Uri>) {
        if (uris.isEmpty() || _state.value is CleanState.Working) return
        _state.value = CleanState.Working(0, uris.size)
        viewModelScope.launch {
            var saved = 0
            var failed = 0
            var converted = 0
            val found = mutableSetOf<Found>()
            uris.forEachIndexed { i, uri ->
                try {
                    withContext(Dispatchers.IO) {
                        val app = getApplication<Application>()
                        val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: error("Could not read $uri")
                        val cleaned = MetadataCleaner.clean(bytes)
                        if (cleaned != null) {
                            found += cleaned.found
                            ImageExporter.saveCopy(app, cleaned.bytes, MetadataCleaner.isPng(bytes), i)
                        } else {
                            val bitmap = ImageLoader.load(app, uri).bitmap
                            val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                            bitmap.recycle()
                            ImageExporter.saveCopy(app, jpeg.toByteArray(), false, i)
                            converted++
                        }
                    }
                    saved++
                } catch (e: Throwable) {
                    // OutOfMemoryError included: one bad photo must not stop the rest
                    Log.w(TAG, "Could not clean $uri", e)
                    failed++
                }
                _state.value = CleanState.Working(i + 1, uris.size)
            }
            _state.value = CleanState.Done(saved, failed, converted, found)
        }
    }

    fun dismiss() {
        if (_state.value is CleanState.Done) _state.value = CleanState.Idle
    }

    private companion object {
        const val TAG = "MetadataViewModel"
    }
}
