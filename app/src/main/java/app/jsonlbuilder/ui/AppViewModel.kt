package app.jsonlbuilder.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.jsonlbuilder.data.Dataset
import app.jsonlbuilder.data.DatasetStore
import app.jsonlbuilder.data.Example
import app.jsonlbuilder.data.ExportFormat
import app.jsonlbuilder.data.ExportOptions
import app.jsonlbuilder.data.Jsonl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = DatasetStore(app)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val pendingSaves = HashMap<String, Job>()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val writer = Dispatchers.IO.limitedParallelism(1)

    val datasets = mutableStateListOf<Dataset>()
    var loaded by mutableStateOf(false)
        private set

    /** One-off messages for the snackbar. */
    val messages = Channel<String>(Channel.BUFFERED)

    var exportOptions by mutableStateOf(loadExportOptions())
        private set

    init {
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) { store.loadAll() }
            datasets.addAll(stored.sortedByDescending { it.updatedAt })
            loaded = true
        }
    }

    fun find(id: String): Dataset? = datasets.firstOrNull { it.id == id }

    // ---- Datasets ----------------------------------------------------------

    fun createDataset(name: String): Dataset {
        val ds = Dataset(name = name.trim().ifEmpty { nextDefaultName() }, examples = listOf(Example()))
        datasets.add(0, ds)
        changed(ds, immediate = true)
        return ds
    }

    fun rename(ds: Dataset, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == ds.name) return
        ds.name = trimmed
        changed(ds)
    }

    fun duplicate(ds: Dataset): Dataset {
        val copy = Dataset(
            name = "${ds.name} (copy)",
            systemPrompt = ds.systemPrompt,
            examples = ds.examples.map { it.copy() },
        )
        datasets.add(0, copy)
        changed(copy, immediate = true)
        return copy
    }

    fun delete(ds: Dataset) {
        pendingSaves.remove(ds.id)?.cancel()
        datasets.remove(ds)
        store.delete(ds.id)
    }

    private fun nextDefaultName(): String {
        val taken = datasets.map { it.name }.toSet()
        return generateSequence(1) { it + 1 }.map { "Dataset $it" }.first { it !in taken }
    }

    // ---- Examples ----------------------------------------------------------

    fun addExample(ds: Dataset): Example {
        val ex = Example()
        ds.examples.add(ex)
        changed(ds)
        return ex
    }

    /** Returns the position the example had, for undo. */
    fun removeExample(ds: Dataset, ex: Example): Int {
        val index = ds.examples.indexOf(ex)
        if (index >= 0) {
            ds.examples.removeAt(index)
            changed(ds)
        }
        return index
    }

    fun restoreExample(ds: Dataset, ex: Example, index: Int) {
        if (ds.examples.any { it.id == ex.id }) return
        ds.examples.add(index.coerceIn(0, ds.examples.size), ex)
        changed(ds)
    }

    fun duplicateExample(ds: Dataset, ex: Example): Example {
        val copy = ex.copy()
        val index = ds.examples.indexOf(ex)
        ds.examples.add(if (index >= 0) index + 1 else ds.examples.size, copy)
        changed(ds)
        return copy
    }

    fun moveExample(ds: Dataset, ex: Example, by: Int) {
        val from = ds.examples.indexOf(ex)
        val to = from + by
        if (from < 0 || to !in ds.examples.indices) return
        ds.examples.add(to, ds.examples.removeAt(from))
        changed(ds)
    }

    // ---- Saving ------------------------------------------------------------

    /**
     * Call after any edit. Saves shortly after typing pauses (or right away), and
     * [flushAll] writes anything still pending when the app goes to the background.
     */
    fun changed(ds: Dataset, immediate: Boolean = false) {
        ds.updatedAt = System.currentTimeMillis()
        ds.revision++
        pendingSaves.remove(ds.id)?.cancel()
        pendingSaves[ds.id] = viewModelScope.launch {
            if (!immediate) delay(SAVE_DELAY_MS)
            val revision = ds.revision
            val json = ds.toJson()
            try {
                withContext(writer) { store.write(ds.id, revision, json) }
            } catch (e: IOException) {
                Log.e(TAG, "Save failed", e)
                messages.trySend("Couldn't save: ${e.message}")
            }
            if (pendingSaves[ds.id] == coroutineContext[Job]) pendingSaves.remove(ds.id)
        }
    }

    fun flushAll() {
        for (id in pendingSaves.keys.toList()) {
            pendingSaves.remove(id)?.cancel()
            val ds = find(id) ?: continue
            try {
                store.write(ds.id, ds.revision, ds.toJson())
            } catch (e: IOException) {
                Log.e(TAG, "Save failed", e)
            }
        }
    }

    override fun onCleared() {
        flushAll()
    }

    // ---- Export / import ---------------------------------------------------

    fun updateExportOptions(options: ExportOptions) {
        exportOptions = options
        prefs.edit {
            putString(KEY_FORMAT, options.format.name)
            putBoolean(KEY_SKIP_INCOMPLETE, options.skipIncomplete)
            putBoolean(KEY_TRIM, options.trimWhitespace)
        }
    }

    private fun loadExportOptions(): ExportOptions {
        val defaults = ExportOptions()
        return ExportOptions(
            format = ExportFormat.entries.firstOrNull { it.name == prefs.getString(KEY_FORMAT, null) }
                ?: defaults.format,
            skipIncomplete = prefs.getBoolean(KEY_SKIP_INCOMPLETE, defaults.skipIncomplete),
            trimWhitespace = prefs.getBoolean(KEY_TRIM, defaults.trimWhitespace),
        )
    }

    suspend fun writeToUri(uri: Uri, text: String): Boolean = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        try {
            val out = try {
                resolver.openOutputStream(uri, "wt")
            } catch (_: Exception) {
                resolver.openOutputStream(uri, "w")
            }
            out?.use { it.write(text.encodeToByteArray()) } ?: return@withContext false
            true
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            false
        }
    }

    class ImportOutcome(val dataset: Dataset?, val message: String)

    suspend fun importFrom(uri: Uri): ImportOutcome {
        val app = getApplication<Application>()
        val (fileName, text) = try {
            withContext(Dispatchers.IO) {
                val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IOException("Couldn't open the file")
                name to bytes.decodeToString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            return ImportOutcome(null, "Couldn't read that file")
        }

        val result = withContext(Dispatchers.Default) { Jsonl.parse(text) }
        if (result.pairs.isEmpty()) {
            return ImportOutcome(null, "No input/response pairs found in that file")
        }
        val ds = Dataset(
            name = fileName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: nextDefaultName(),
            systemPrompt = result.systemPrompt,
            examples = result.pairs.map { (input, output) -> Example(input = input, output = output) },
        )
        datasets.add(0, ds)
        changed(ds, immediate = true)
        val skipped = if (result.skippedLines > 0) " · skipped ${result.skippedLines} unreadable lines" else ""
        return ImportOutcome(ds, "Imported ${result.pairs.size} examples$skipped")
    }

    private companion object {
        const val TAG = "JsonlBuilder"
        const val SAVE_DELAY_MS = 400L
        const val KEY_FORMAT = "export_format"
        const val KEY_SKIP_INCOMPLETE = "export_skip_incomplete"
        const val KEY_TRIM = "export_trim"
    }
}
