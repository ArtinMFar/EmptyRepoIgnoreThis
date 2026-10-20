package app.imagetosvg.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.imagetosvg.image.ImageLoader
import app.imagetosvg.image.LoadedImage
import app.imagetosvg.image.Pixels
import app.imagetosvg.shapes.Box
import app.imagetosvg.shapes.Oval
import app.imagetosvg.shapes.ShapeKind
import app.imagetosvg.shapes.ShapeSearch
import app.imagetosvg.shapes.Triangle
import app.imagetosvg.trace.ExactSvg
import app.imagetosvg.trace.SvgCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

enum class Mode { EXACT, BRUTE_FORCE }

/** How a result is saved. Both hold the same SVG markup. */
enum class FileType(val extension: String, val mimeType: String) {
    SVG("svg", "image/svg+xml"),
    /** Plain text, for apps and sites that only take .txt files. */
    TEXT("txt", "text/plain"),
}

class OpenImage(
    val image: LoadedImage,
    val preview: ImageBitmap,
    val analysis: ExactSvg.Analysis,
) {
    val name get() = image.name
    val width get() = image.width
    val height get() = image.height
}

sealed interface ExactState {
    data object Idle : ExactState

    /** Waiting for a go-ahead because the file would be huge. */
    class TooBig(val estimatedBytes: Long) : ExactState

    /** [progress] null = indeterminate. */
    class Working(val phase: String, val progress: Float?) : ExactState

    class Done(
        val file: File,
        val bytes: Long,
        val stats: ExactSvg.Stats,
        /** Pixels that came out different when the SVG was re-rendered. Always 0 unless something is broken. */
        val mismatched: Int,
        /** The SVG as re-rendered by the check, for the preview. */
        val rendered: ImageBitmap,
    ) : ExactState

    class Failed(val message: String) : ExactState
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var loading by mutableStateOf(false)
        private set
    var current by mutableStateOf<OpenImage?>(null)
        private set
    var mode by mutableStateOf(enumOr(prefs.getString(KEY_MODE, null), Mode.EXACT))
        private set

    var exact by mutableStateOf<ExactState>(ExactState.Idle)
        private set

    var kind by mutableStateOf(enumOr(prefs.getString(KEY_KIND, null), ShapeKind.TRIANGLES))
        private set
    /** Stop after this many shapes; 0 = keep going until stopped. */
    var limit by mutableIntStateOf(prefs.getInt(KEY_LIMIT, 500))
        private set
    var running by mutableStateOf(false)
        private set
    var shapeCount by mutableIntStateOf(0)
        private set
    var similarity by mutableDoubleStateOf(0.0)
        private set
    /** Fraction of the working copy's pixels that are exactly right. */
    var exactFraction by mutableDoubleStateOf(0.0)
        private set
    var brutePreview by mutableStateOf<ImageBitmap?>(null)
        private set
    /** Bumped whenever [brutePreview]'s pixels change, so the screen redraws it. */
    var bruteVersion by mutableIntStateOf(0)
        private set
    var bruteFinished by mutableStateOf<String?>(null)
        private set

    /** One-off messages for the snackbar. */
    val messages = Channel<String>(Channel.BUFFERED)

    private var loadJob: Job? = null
    private var exactJob: Job? = null
    private var bruteJob: Job? = null
    private var search: ShapeSearch? = null
    private val searchLock = Mutex()
    private var bruteCanvas: Canvas? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    // ---- Opening ------------------------------------------------------------

    fun open(uri: Uri) {
        loadJob?.cancel()
        exactJob?.cancel()
        resetBruteForce()
        exact = ExactState.Idle
        current = null
        loading = true
        loadJob = viewModelScope.launch {
            try {
                val image = withContext(Dispatchers.IO) { ImageLoader.load(getApplication(), uri) }
                show(image)
                image.originalSize?.let { (w, h) ->
                    messages.trySend("That image is $w × $h, so it was opened at ${image.width} × ${image.height}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Open failed", e)
                messages.trySend("That image is too big to open on this phone")
            } catch (e: Exception) {
                Log.e(TAG, "Open failed", e)
                messages.trySend("Couldn't open that image")
            } finally {
                loading = false
            }
        }
    }

    /** Shows an already decoded image (also used by tests). */
    suspend fun show(image: LoadedImage) {
        val opened = withContext(Dispatchers.Default) {
            OpenImage(
                image = image,
                preview = ImageLoader.displayBitmap(image.pixels, image.width, image.height, PREVIEW_MAX).asImageBitmap(),
                analysis = ExactSvg.analyze(image.pixels, image.width, image.height),
            )
        }
        withContext(Dispatchers.IO) { exportsDir().listFiles()?.forEach { it.delete() } }
        current = opened
        exact = ExactState.Idle
        if (mode == Mode.EXACT) startExactIfSmall()
    }

    fun selectMode(m: Mode) {
        mode = m
        prefs.edit { putString(KEY_MODE, m.name) }
        if (m == Mode.EXACT && exact is ExactState.Idle) startExactIfSmall()
    }

    // ---- Exact --------------------------------------------------------------

    private fun startExactIfSmall() {
        val img = current ?: return
        val estimate = img.analysis.estimatedBytes
        if (estimate > AUTO_CONVERT_BYTES) exact = ExactState.TooBig(estimate) else convertExact()
    }

    fun convertExact() {
        val img = current ?: return
        exactJob?.cancel()
        exact = ExactState.Working("Tracing pixels", 0f)
        exactJob = viewModelScope.launch {
            exact = try {
                withContext(Dispatchers.Default) {
                    val w = img.width
                    val h = img.height
                    val file = File(exportsDir(), "${img.name}.svg")
                    val stats = file.bufferedWriter(bufferSize = 1 shl 16).use { out ->
                        ExactSvg.write(img.image.pixels, w, h, out) { p ->
                            ensureActive()
                            exact = ExactState.Working("Tracing pixels", p)
                        }
                    }
                    exact = ExactState.Working("Checking every pixel", null)
                    val check = file.bufferedReader(bufferSize = 1 shl 16).use {
                        SvgCheck.verify(it, img.image.pixels, w, h)
                    }
                    ensureActive()
                    ExactState.Done(
                        file = file,
                        bytes = file.length(),
                        stats = stats,
                        mismatched = check.mismatched,
                        rendered = ImageLoader.displayBitmap(check.rendered, w, h, PREVIEW_MAX).asImageBitmap(),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Exact conversion failed", e)
                ExactState.Failed("Not enough memory for an exact SVG of an image this big.")
            } catch (e: IOException) {
                Log.e(TAG, "Exact conversion failed", e)
                ExactState.Failed("Couldn't write the SVG: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Exact conversion failed", e)
                ExactState.Failed("Something went wrong: ${e.message}")
            }
        }
    }

    // ---- Brute force ----------------------------------------------------------

    fun selectKind(k: ShapeKind) {
        kind = k
        prefs.edit { putString(KEY_KIND, k.name) }
    }

    fun selectLimit(n: Int) {
        limit = n
        prefs.edit { putInt(KEY_LIMIT, n) }
        bruteFinished = null
    }

    fun startBruteForce() {
        val img = current ?: return
        if (running) return
        running = true
        bruteFinished = null
        bruteJob = viewModelScope.launch {
            try {
                val s = search ?: withContext(Dispatchers.Default) { newSearch(img) }
                var misses = 0
                while (isActive) {
                    if (s.isExact) {
                        bruteFinished = "Exact match"
                        break
                    }
                    if (limit != 0 && shapeCount >= limit) {
                        bruteFinished = "Reached $limit shapes"
                        break
                    }
                    val placed = searchLock.withLock {
                        withContext(Dispatchers.Default) { s.step(kind) }
                    }
                    if (placed == null) {
                        if (++misses < 5) continue
                        bruteFinished = "No shape makes it any closer"
                        break
                    }
                    misses = 0
                    bruteCanvas?.let { drawShape(it, placed) }
                    shapeCount = s.shapes.size
                    similarity = s.similarity
                    exactFraction = s.exactPixels.toDouble() / (s.width * s.height)
                    bruteVersion++
                }
            } finally {
                running = false
            }
        }
    }

    fun stopBruteForce() {
        bruteJob?.cancel()
    }

    fun resetBruteForce() {
        bruteJob?.cancel()
        bruteJob = null
        search = null
        bruteCanvas = null
        brutePreview = null
        shapeCount = 0
        similarity = 0.0
        exactFraction = 0.0
        bruteFinished = null
        running = false
    }

    private suspend fun newSearch(img: OpenImage): ShapeSearch {
        val (tw, th) = Pixels.fit(img.width, img.height, ShapeSearch.WORKING_SIZE)
        val target = Pixels.onWhite(Pixels.scale(img.image.pixels, img.width, img.height, tw, th))
        val s = ShapeSearch(target, tw, th)
        // Draw the preview at a fixed size, so tiny images still get smooth shapes.
        val longest = maxOf(img.width, img.height).toFloat()
        val pw = (BRUTE_PREVIEW_SIDE * img.width / longest).toInt().coerceAtLeast(1)
        val ph = (BRUTE_PREVIEW_SIDE * img.height / longest).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply {
            scale(pw.toFloat() / tw, ph.toFloat() / th)
            drawColor(0xFF000000.toInt() or s.background)
        }
        withContext(Dispatchers.Main) {
            search = s
            bruteCanvas = canvas
            brutePreview = bitmap.asImageBitmap()
            similarity = s.similarity
            exactFraction = s.exactPixels.toDouble() / (tw * th)
        }
        return s
    }

    private fun drawShape(canvas: Canvas, placed: ShapeSearch.Placed) {
        paint.color = 0xFF000000.toInt() or placed.color
        paint.alpha = ShapeSearch.ALPHA
        when (val s = placed.shape) {
            is Triangle -> {
                path.reset()
                path.moveTo(s.x1, s.y1)
                path.lineTo(s.x2, s.y2)
                path.lineTo(s.x3, s.y3)
                path.close()
                canvas.drawPath(path, paint)
            }
            is Box -> {
                val (xs, ys) = s.corners()
                path.reset()
                path.moveTo(xs[0], ys[0])
                for (i in 1 until 4) path.lineTo(xs[i], ys[i])
                path.close()
                canvas.drawPath(path, paint)
            }
            is Oval -> {
                canvas.save()
                canvas.rotate(s.angle, s.cx, s.cy)
                canvas.drawOval(s.cx - s.rx, s.cy - s.ry, s.cx + s.rx, s.cy + s.ry, paint)
                canvas.restore()
            }
        }
    }

    // ---- Export -------------------------------------------------------------

    fun fileName(m: Mode, type: FileType = FileType.SVG): String {
        val name = current?.name ?: "image"
        val base = if (m == Mode.EXACT) name else "$name-$shapeCount-shapes"
        return "$base.${type.extension}"
    }

    /** The SVG for [m] as a file in the app's cache, or null if there isn't one yet. */
    suspend fun svgFile(m: Mode): File? = when (m) {
        Mode.EXACT -> (exact as? ExactState.Done)?.file
        Mode.BRUTE_FORCE -> {
            val s = search
            val img = current
            if (s == null || img == null || shapeCount == 0) {
                null
            } else {
                val name = fileName(m)
                val text = searchLock.withLock { withContext(Dispatchers.Default) { s.svg(img.width, img.height) } }
                withContext(Dispatchers.IO) { File(exportsDir(), name).apply { writeText(text) } }
            }
        }
    }

    suspend fun saveTo(uri: Uri, m: Mode): Boolean {
        val file = svgFile(m) ?: return false
        return withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            try {
                val out = try {
                    resolver.openOutputStream(uri, "wt")
                } catch (_: Exception) {
                    resolver.openOutputStream(uri, "w")
                }
                out?.use { o -> file.inputStream().use { it.copyTo(o, 1 shl 16) } } ?: return@withContext false
                true
            } catch (e: Exception) {
                Log.e(TAG, "Save failed", e)
                false
            }
        }
    }

    private fun exportsDir() = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }

    override fun onCleared() {
        bruteJob?.cancel()
    }

    companion object {
        private const val TAG = "ImageToSvg"
        private const val KEY_MODE = "mode"
        private const val KEY_KIND = "shape_kind"
        private const val KEY_LIMIT = "shape_limit"

        /** Longest side of on-screen previews. */
        const val PREVIEW_MAX = 1440
        const val BRUTE_PREVIEW_SIDE = 1080

        /** Bigger exact SVGs wait for a tap instead of starting by themselves. */
        const val AUTO_CONVERT_BYTES = 25L * 1024 * 1024

        val LIMITS = listOf(100, 250, 500, 1000, 2000, 0)

        private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: default
    }
}
