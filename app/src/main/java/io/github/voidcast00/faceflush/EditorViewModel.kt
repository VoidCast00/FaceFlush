package io.github.voidcast00.faceflush

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.voidcast00.faceflush.detect.DetectedFace
import io.github.voidcast00.faceflush.detect.DetectorKind
import io.github.voidcast00.faceflush.detect.FaceDetector
import io.github.voidcast00.faceflush.detect.FaceDetectors
import io.github.voidcast00.faceflush.detect.detectDownscaled
import io.github.voidcast00.faceflush.effects.Box
import io.github.voidcast00.faceflush.effects.CanonicalField
import io.github.voidcast00.faceflush.effects.FacePattern
import io.github.voidcast00.faceflush.effects.Landmarks
import io.github.voidcast00.faceflush.effects.InvisibleAssets
import io.github.voidcast00.faceflush.effects.EditorMode
import io.github.voidcast00.faceflush.effects.EffectRenderer
import io.github.voidcast00.faceflush.effects.Params
import io.github.voidcast00.faceflush.effects.PixelBuffer
import io.github.voidcast00.faceflush.effects.Region
import io.github.voidcast00.faceflush.effects.RegionMode
import io.github.voidcast00.faceflush.effects.Strength
import io.github.voidcast00.faceflush.effects.Technique
import io.github.voidcast00.faceflush.io.ImageExporter
import io.github.voidcast00.faceflush.io.ImageLoader
import io.github.voidcast00.faceflush.max.MaxPrivacyWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.random.Random

/** [pattern] is set once Max privacy has optimised a cloak for this face. */
data class FaceItem(val face: DetectedFace, val enabled: Boolean = true, val pattern: FacePattern? = null)

data class MaxState(
    val running: Boolean = false,
    /** Null until the first progress report. */
    val percent: Int? = null,
    val secondsLeft: Int? = null,
)

data class EditorState(
    val loading: Boolean = false,
    /** Downscaled original, used for the preview and "hold to compare". */
    val original: Bitmap? = null,
    val preview: Bitmap? = null,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val faces: List<FaceItem> = emptyList(),
    val manualBoxes: List<Box> = emptyList(),
    val mode: EditorMode = EditorMode.VISIBLE,
    val technique: Technique = Technique.PIXELATE,
    val regionMode: RegionMode = Technique.PIXELATE.defaultRegion,
    /** Null once the user has moved a slider by hand. */
    val strength: Strength? = Strength.MEDIUM,
    val params: Map<String, Float> = Technique.PIXELATE.paramsFor(Strength.MEDIUM),
    val detecting: Boolean = false,
    val exporting: Boolean = false,
    val detector: DetectorKind = DetectorKind.YUNET,
    val max: MaxState = MaxState(),
) {
    val maxApplied get() = faces.count { it.pattern != null }

    val hasImage get() = original != null || loading
}

sealed interface EditorEvent {
    data class Message(val text: Int) : EditorEvent
    data class Share(val uri: Uri, val mimeType: String) : EditorEvent
}

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        EditorState(detector = DetectorKind.entries[prefs.getInt(KEY_DETECTOR, 0).coerceIn(0, 1)]),
    )
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private val _events = Channel<EditorEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var source: Bitmap? = null
    private var sourceIsPng = false
    private var previewScale = 1f
    private var seed = 0L
    private var detector: Pair<FaceDetector, DetectorKind>? = null
    private var renderJob: Job? = null
    private var detectJob: Job? = null
    private var sourceUri: Uri? = null
    private val work = WorkManager.getInstance(app)

    /** Face indices a running Max privacy job works on, in job order. */
    private var maxFaces: List<Int> = emptyList()

    /** Trained cloak pattern and morph field from the lab; missing files just disable that part. */
    private val invisibleAssets by lazy {
        fun load(name: String) = try {
            app.assets.open(name).use(CanonicalField::read)
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Missing asset $name", e)
            null
        }
        InvisibleAssets(cloak = load("cloak_pattern.bin"), morph = load("morph_field.bin"))
    }

    init {
        viewModelScope.launch(Dispatchers.IO) { ImageExporter.clearShareCache(app) }
        maxFaces = prefs.getString(KEY_MAX_FACES, "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }
        viewModelScope.launch {
            work.getWorkInfosForUniqueWorkFlow(MaxPrivacyWorker.WORK_NAME).collect { infos ->
                infos.firstOrNull()?.let(::onMaxInfo)
            }
        }
    }

    /** Starts Max privacy for the enabled faces at the current cloak strength. */
    fun startMax() {
        val src = source ?: return
        val s = _state.value
        val faces = s.faces.withIndex().filter { it.value.enabled }
        if (faces.isEmpty()) {
            _events.trySend(EditorEvent.Message(R.string.max_no_faces))
            return
        }
        val eps = (s.params[Params.VISIBILITY] ?: 16f) / 255f
        val rainbow = (s.params[Params.RAINBOW] ?: 0f) >= 1f
        _state.update { it.copy(max = MaxState(running = true)) }
        viewModelScope.launch {
            // The job works on a copy of the photo at detection size, so it survives the app closing
            val (buf, scale) = withContext(Dispatchers.Default) {
                val scale = minOf(1f, DETECT_SIDE.toFloat() / max(src.width, src.height))
                val small = if (scale < 1f) {
                    Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
                } else src
                val pixels = IntArray(small.width * small.height)
                small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
                if (small !== src) small.recycle()
                PixelBuffer(small.width, small.height, pixels) to scale
            }
            withContext(Dispatchers.IO) { MaxPrivacyWorker.writeSource(getApplication(), buf) }
            maxFaces = faces.map { it.index }
            prefs.edit()
                .putString(KEY_MAX_FACES, maxFaces.joinToString(","))
                .putString(KEY_MAX_URI, sourceUri?.toString())
                .apply()
            val landmarks = faces.map { (it.value.face.landmarks ?: Landmarks.estimate(it.value.face.box)).scaled(scale) }
            val request = OneTimeWorkRequestBuilder<MaxPrivacyWorker>()
                .setInputData(MaxPrivacyWorker.inputData(landmarks, eps, rainbow, seed))
                .build()
            work.enqueueUniqueWork(MaxPrivacyWorker.WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }

    fun cancelMax() {
        work.cancelUniqueWork(MaxPrivacyWorker.WORK_NAME)
    }

    private fun onMaxInfo(info: WorkInfo) {
        when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> {
                val p = info.progress
                val percent = if (p.keyValueMap.containsKey(MaxPrivacyWorker.KEY_PROGRESS)) {
                    p.getInt(MaxPrivacyWorker.KEY_PROGRESS, 0)
                } else null
                val left = if (percent != null) p.getInt(MaxPrivacyWorker.KEY_SECONDS_LEFT, 0) else null
                _state.update { it.copy(max = MaxState(running = true, percent = percent, secondsLeft = left)) }
            }
            WorkInfo.State.SUCCEEDED -> {
                _state.update { it.copy(max = MaxState()) }
                if (prefs.getString(KEY_MAX_DONE, null) == info.id.toString()) return
                val count = info.outputData.getInt(MaxPrivacyWorker.KEY_FACES, 0)
                val eps = info.outputData.getFloat(MaxPrivacyWorker.KEY_EPS, 0f)
                val rainbow = info.outputData.getBoolean(MaxPrivacyWorker.KEY_RAINBOW, false)
                viewModelScope.launch { applyMaxResults(info.id.toString(), count, eps, rainbow) }
            }
            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                val wasRunning = _state.value.max.running
                _state.update { it.copy(max = MaxState()) }
                if (wasRunning && info.state == WorkInfo.State.FAILED) {
                    _events.trySend(EditorEvent.Message(R.string.max_failed))
                }
            }
        }
    }

    /** Puts finished Max privacy patterns on their faces, reopening the photo if the app was closed. */
    private suspend fun applyMaxResults(id: String, count: Int, eps: Float, rainbow: Boolean) {
        val fields = withContext(Dispatchers.IO) { MaxPrivacyWorker.readResults(getApplication(), count) } ?: return
        val jobUri = prefs.getString(KEY_MAX_URI, null)?.let(Uri::parse) ?: return
        if (source == null && !_state.value.loading) open(jobUri)
        // Wait for the photo and its faces (detection gives the same face order as when the job started)
        while (_state.value.loading || _state.value.detecting) delay(100)
        prefs.edit().putString(KEY_MAX_DONE, id).apply()
        // Another photo was opened meanwhile: the result belongs to a different picture
        if (source == null || sourceUri != jobUri) return
        val byFace = maxFaces.zip(fields).toMap()
        updateAndRender { s ->
            val faces = s.faces.mapIndexed { i, f -> byFace[i]?.let { f.copy(pattern = FacePattern(it, eps, rainbow)) } ?: f }
            s.copy(
                faces = faces,
                mode = EditorMode.INVISIBLE,
                technique = Technique.INVISIBLE,
                regionMode = RegionMode.FACES,
                params = if (s.technique == Technique.INVISIBLE) s.params else Technique.INVISIBLE.paramsFor(Strength.MEDIUM),
                strength = if (s.technique == Technique.INVISIBLE) s.strength else Strength.MEDIUM,
            )
        }
    }

    fun open(uri: Uri) {
        close()
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { ImageLoader.load(getApplication(), uri) }
                sourceUri = uri
                try {
                    // Lets a finished Max privacy job reopen this photo after the app was closed
                    getApplication<Application>().contentResolver
                        .takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: SecurityException) {
                    Log.i(TAG, "No persistable permission for $uri")
                }
                val bmp = loaded.bitmap
                val scale = minOf(1f, PREVIEW_SIDE.toFloat() / max(bmp.width, bmp.height))
                val preview = withContext(Dispatchers.Default) {
                    if (scale < 1f) {
                        Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                    } else bmp
                }
                source = bmp
                sourceIsPng = loaded.isPng
                previewScale = preview.width.toFloat() / bmp.width
                seed = Random.nextLong()
                _state.update {
                    it.copy(
                        loading = false,
                        original = preview,
                        preview = preview,
                        sourceWidth = bmp.width,
                        sourceHeight = bmp.height,
                    )
                }
                detectFaces()
            } catch (e: Exception) {
                Log.w(TAG, "Could not open image", e)
                _state.update { it.copy(loading = false) }
                _events.send(EditorEvent.Message(R.string.error_open))
            }
        }
    }

    fun close() {
        renderJob?.cancel()
        detectJob?.cancel()
        source = null
        _state.update {
            it.copy(
                loading = false, original = null, preview = null, faces = emptyList(),
                manualBoxes = emptyList(), detecting = false, exporting = false,
            )
        }
    }

    /** Switches between hiding faces visibly and the invisible cloak, keeping the photo. */
    fun setMode(mode: EditorMode) {
        if (_state.value.mode == mode) return
        val technique = if (mode == EditorMode.INVISIBLE) Technique.INVISIBLE else Technique.PIXELATE
        selectTechnique(technique)
        _state.update { it.copy(mode = mode) }
    }

    fun selectTechnique(technique: Technique) = updateAndRender {
        it.copy(
            technique = technique,
            regionMode = technique.defaultRegion,
            strength = Strength.MEDIUM,
            params = technique.paramsFor(Strength.MEDIUM),
        )
    }

    fun selectRegionMode(mode: RegionMode) = updateAndRender {
        if (mode in it.technique.regionModes) it.copy(regionMode = mode) else it
    }

    fun selectStrength(strength: Strength) = updateAndRender {
        it.copy(strength = strength, params = it.technique.paramsFor(strength, it.params))
    }

    fun setParam(key: String, value: Float) = updateAndRender {
        val isChoice = it.technique.params.firstOrNull { spec -> spec.key == key }?.options != null
        it.copy(params = it.params + (key to value), strength = if (isChoice) it.strength else null)
    }

    fun toggleFace(index: Int) = updateAndRender {
        it.copy(faces = it.faces.mapIndexed { i, f -> if (i == index) f.copy(enabled = !f.enabled) else f })
    }

    /** [box] is in source-image coordinates. Drawing an area switches "Whole image" over to "Manual". */
    fun addManualBox(box: Box) = updateAndRender {
        val mode = if (it.regionMode == RegionMode.WHOLE_IMAGE) RegionMode.MANUAL else it.regionMode
        it.copy(manualBoxes = it.manualBoxes + box, regionMode = mode)
    }

    fun removeManualBox(index: Int) = updateAndRender {
        it.copy(manualBoxes = it.manualBoxes.filterIndexed { i, _ -> i != index })
    }

    fun setDetector(kind: DetectorKind) {
        prefs.edit().putInt(KEY_DETECTOR, kind.ordinal).apply()
        detector = null
        _state.update { it.copy(detector = kind) }
        if (source != null) detectFaces()
    }

    fun save() = export { bitmap, png ->
        ImageExporter.saveToGallery(getApplication(), bitmap, png)
        _events.send(EditorEvent.Message(R.string.saved))
    }

    fun share() = export { bitmap, png ->
        val uri = ImageExporter.writeForSharing(getApplication(), bitmap, png)
        _events.send(EditorEvent.Share(uri, ImageExporter.mimeType(png)))
    }

    private fun export(block: suspend (Bitmap, Boolean) -> Unit) {
        val src = source ?: return
        if (_state.value.exporting) return
        _state.update { it.copy(exporting = true) }
        val snapshot = _state.value
        val png = sourceIsPng
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) { renderOnto(src, 1f, snapshot) }
                try {
                    withContext(Dispatchers.IO) { block(result, png) }
                } finally {
                    result.recycle()
                }
            } catch (e: Throwable) {
                // OutOfMemoryError included: a failed export must not crash the app
                Log.w(TAG, "Export failed", e)
                _events.send(EditorEvent.Message(R.string.error_export))
            } finally {
                _state.update { it.copy(exporting = false) }
            }
        }
    }

    private fun detectFaces() {
        val src = source ?: return
        detectJob?.cancel()
        _state.update { it.copy(detecting = true, faces = emptyList()) }
        detectJob = viewModelScope.launch {
            val (faces, kind) = withContext(Dispatchers.Default) {
                val d = detector ?: FaceDetectors.create(getApplication(), _state.value.detector).also { detector = it }
                val found = try {
                    d.first.detectDownscaled(src, DETECT_SIDE)
                } catch (e: Exception) {
                    Log.w(TAG, "Face detection failed", e)
                    emptyList()
                }
                found to d.second
            }
            if (kind != _state.value.detector) {
                _events.send(EditorEvent.Message(R.string.detector_fallback))
            }
            _state.update { it.copy(detecting = false, faces = faces.map { f -> FaceItem(f) }) }
            schedulePreview()
            if (faces.isEmpty()) _events.send(EditorEvent.Message(R.string.no_faces))
        }
    }

    private fun updateAndRender(change: (EditorState) -> EditorState) {
        _state.update(change)
        schedulePreview()
    }

    private fun schedulePreview() {
        val original = _state.value.original ?: return
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            delay(PREVIEW_DEBOUNCE_MS)
            val snapshot = _state.value
            val rendered = withContext(Dispatchers.Default) { renderOnto(original, previewScale, snapshot) }
            _state.update { if (it.original === original) it.copy(preview = rendered) else it }
        }
    }

    private fun renderOnto(base: Bitmap, scale: Float, s: EditorState): Bitmap {
        val w = base.width
        val h = base.height
        val pixels = IntArray(w * h)
        base.getPixels(pixels, 0, w, 0, 0, w, h)
        val assets = if (s.technique == Technique.INVISIBLE) invisibleAssets else null
        EffectRenderer.render(PixelBuffer(w, h, pixels), s.technique, s.params, regionsFor(s), scale, seed, assets)
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun regionsFor(s: EditorState): List<Region> {
        val manual = s.manualBoxes.map { Region(it) }
        return when (s.regionMode) {
            RegionMode.WHOLE_IMAGE ->
                listOf(Region(Box(0f, 0f, s.sourceWidth.toFloat(), s.sourceHeight.toFloat()), wholeImage = true))
            RegionMode.FACES -> s.faces.filter { it.enabled }.map { Region(it.face.box, it.face.landmarks, pattern = it.pattern) } + manual
            RegionMode.MANUAL -> manual
        }
    }

    private companion object {
        const val TAG = "EditorViewModel"
        const val KEY_DETECTOR = "detector"
        const val KEY_MAX_FACES = "max_faces"
        const val KEY_MAX_URI = "max_uri"
        const val KEY_MAX_DONE = "max_done"
        const val PREVIEW_SIDE = 1280
        const val DETECT_SIDE = 1600
        const val PREVIEW_DEBOUNCE_MS = 60L
    }
}
