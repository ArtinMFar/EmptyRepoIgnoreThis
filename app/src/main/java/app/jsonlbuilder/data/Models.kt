package app.jsonlbuilder.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

/** One training example: what the user said and what the model should answer. */
class Example(
    val id: String = newId(),
    input: String = "",
    output: String = "",
) {
    var input by mutableStateOf(input)
    var output by mutableStateOf(output)

    val isComplete: Boolean get() = input.isNotBlank() && output.isNotBlank()
    val isEmpty: Boolean get() = input.isBlank() && output.isBlank()

    fun copy() = Example(input = input, output = output)
}

class Dataset(
    val id: String = newId(),
    name: String,
    systemPrompt: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    updatedAt: Long = createdAt,
    examples: List<Example> = emptyList(),
) {
    var name by mutableStateOf(name)
    var systemPrompt by mutableStateOf(systemPrompt)
    var updatedAt by mutableLongStateOf(updatedAt)
    val examples = mutableStateListOf<Example>().apply { addAll(examples) }

    /** Bumped on every edit so stale background saves can be told apart from fresh ones. */
    internal var revision = 0L

    val completeCount: Int get() = examples.count { it.isComplete }

    fun pairs(): List<Pair<String, String>> = examples.map { it.input to it.output }

    fun toJson(): String = JSONObject()
        .put("version", 1)
        .put("id", id)
        .put("name", name)
        .put("systemPrompt", systemPrompt)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)
        .put(
            "examples",
            JSONArray().apply {
                examples.forEach {
                    put(JSONObject().put("id", it.id).put("input", it.input).put("output", it.output))
                }
            },
        )
        .toString()

    companion object {
        fun fromJson(text: String): Dataset {
            val o = JSONObject(text)
            val arr = o.optJSONArray("examples") ?: JSONArray()
            val examples = (0 until arr.length()).map { i ->
                val e = arr.getJSONObject(i)
                Example(
                    id = e.optString("id").ifEmpty { newId() },
                    input = e.optString("input"),
                    output = e.optString("output"),
                )
            }
            val createdAt = o.optLong("createdAt", System.currentTimeMillis())
            return Dataset(
                id = o.getString("id"),
                name = o.optString("name", "Untitled"),
                systemPrompt = o.optString("systemPrompt"),
                createdAt = createdAt,
                updatedAt = o.optLong("updatedAt", createdAt),
                examples = examples,
            )
        }
    }
}
