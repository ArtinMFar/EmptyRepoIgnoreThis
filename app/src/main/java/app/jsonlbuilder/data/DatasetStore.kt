package app.jsonlbuilder.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Keeps each dataset in its own JSON file inside the app's private storage.
 * Writes go through [AtomicFile], so a crash or a killed process mid-write never
 * leaves a half-written file behind.
 */
class DatasetStore(context: Context) {
    private val dir = File(context.filesDir, "datasets").apply { mkdirs() }
    private val lock = Any()
    private val writtenRevision = HashMap<String, Long>()
    private val deleted = HashSet<String>()

    fun loadAll(): List<Dataset> {
        val files = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        return files.mapNotNull { f ->
            try {
                Dataset.fromJson(AtomicFile(f).readFully().decodeToString())
            } catch (e: Exception) {
                Log.e(TAG, "Skipping unreadable dataset file ${f.name}", e)
                null
            }
        }
    }

    /** Writes [json] unless a newer revision of the same dataset was already written. */
    @Throws(IOException::class)
    fun write(id: String, revision: Long, json: String) = synchronized(lock) {
        if (id in deleted || revision <= (writtenRevision[id] ?: -1L)) return
        val file = AtomicFile(File(dir, "$id.json"))
        val out = file.startWrite()
        try {
            out.write(json.encodeToByteArray())
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            throw e
        }
        writtenRevision[id] = revision
    }

    fun delete(id: String) = synchronized(lock) {
        deleted += id
        AtomicFile(File(dir, "$id.json")).delete()
    }

    private companion object {
        const val TAG = "DatasetStore"
    }
}
