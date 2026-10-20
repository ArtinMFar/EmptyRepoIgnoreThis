package app.jsonlbuilder.data

import org.json.JSONArray
import org.json.JSONObject

enum class ExportFormat(
    val label: String,
    val sample: String,
    val usesSystemPrompt: Boolean,
) {
    CHAT(
        label = "Chat messages",
        sample = """{"messages": [{"role": "user", …}, {"role": "assistant", …}]}""",
        usesSystemPrompt = true,
    ),
    PROMPT_COMPLETION(
        label = "Prompt / completion",
        sample = """{"prompt": …, "completion": …}""",
        usesSystemPrompt = false,
    ),
    INPUT_OUTPUT(
        label = "Input / output",
        sample = """{"input": …, "output": …}""",
        usesSystemPrompt = false,
    ),
    ALPACA(
        label = "Alpaca",
        sample = """{"instruction": …, "input": "", "output": …}""",
        usesSystemPrompt = false,
    ),
}

data class ExportOptions(
    val format: ExportFormat = ExportFormat.CHAT,
    val skipIncomplete: Boolean = true,
    val trimWhitespace: Boolean = true,
)

data class ImportResult(
    val systemPrompt: String,
    val pairs: List<Pair<String, String>>,
    val skippedLines: Int,
)

object Jsonl {

    /** One JSON object per example, ready to be joined with newlines. */
    fun lines(
        systemPrompt: String,
        pairs: List<Pair<String, String>>,
        options: ExportOptions,
    ): List<String> {
        val clean: (String) -> String = if (options.trimWhitespace) String::trim else { s -> s }
        val system = clean(systemPrompt)
        return pairs
            .map { (input, output) -> clean(input) to clean(output) }
            .filter { (input, output) ->
                if (options.skipIncomplete) input.isNotBlank() && output.isNotBlank()
                else input.isNotBlank() || output.isNotBlank()
            }
            .map { (input, output) -> line(options.format, system, input, output) }
    }

    fun build(systemPrompt: String, pairs: List<Pair<String, String>>, options: ExportOptions): String {
        val lines = lines(systemPrompt, pairs, options)
        return if (lines.isEmpty()) "" else lines.joinToString(separator = "\n", postfix = "\n")
    }

    private fun line(format: ExportFormat, system: String, input: String, output: String): String =
        when (format) {
            ExportFormat.CHAT -> buildString {
                append("{\"messages\": [")
                if (system.isNotBlank()) {
                    append(message("system", system)).append(", ")
                }
                append(message("user", input)).append(", ")
                append(message("assistant", output))
                append("]}")
            }
            ExportFormat.PROMPT_COMPLETION ->
                "{\"prompt\": ${quote(input)}, \"completion\": ${quote(output)}}"
            ExportFormat.INPUT_OUTPUT ->
                "{\"input\": ${quote(input)}, \"output\": ${quote(output)}}"
            ExportFormat.ALPACA ->
                "{\"instruction\": ${quote(input)}, \"input\": \"\", \"output\": ${quote(output)}}"
        }

    private fun message(role: String, content: String) =
        "{\"role\": ${quote(role)}, \"content\": ${quote(content)}}"

    /** JSON string literal. Non-ASCII text is kept as-is (the file is UTF-8). */
    fun quote(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else ->
                    if (c < ' ' || c == ' ' || c == ' ') append("\\u%04x".format(c.code))
                    else append(c)
            }
        }
        append('"')
    }

    /**
     * Reads JSONL in any of the export formats (plus ShareGPT-style "conversations"
     * and question/answer pairs). Multi-turn chats become one example per user→assistant turn.
     */
    fun parse(text: String): ImportResult {
        var systemPrompt = ""
        val pairs = mutableListOf<Pair<String, String>>()
        var skipped = 0

        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val obj = try {
                JSONObject(line)
            } catch (_: Exception) {
                skipped++
                continue
            }

            val messages = obj.optJSONArray("messages") ?: obj.optJSONArray("conversations")
            val found: List<Pair<String, String>> = when {
                messages != null -> {
                    val (system, turns) = parseMessages(messages)
                    if (systemPrompt.isEmpty() && system != null) systemPrompt = system
                    turns
                }
                obj.has("prompt") && obj.has("completion") ->
                    listOf(text(obj.opt("prompt")) to text(obj.opt("completion")))
                obj.has("instruction") && obj.has("output") -> {
                    val instruction = text(obj.opt("instruction"))
                    val extra = text(obj.opt("input"))
                    val input = if (extra.isBlank()) instruction else "$instruction\n\n$extra"
                    listOf(input to text(obj.opt("output")))
                }
                obj.has("input") && obj.has("output") ->
                    listOf(text(obj.opt("input")) to text(obj.opt("output")))
                obj.has("question") && obj.has("answer") ->
                    listOf(text(obj.opt("question")) to text(obj.opt("answer")))
                else -> emptyList()
            }
            if (found.isEmpty()) skipped++ else pairs += found
        }
        return ImportResult(systemPrompt, pairs, skipped)
    }

    private fun parseMessages(messages: JSONArray): Pair<String?, List<Pair<String, String>>> {
        var system: String? = null
        val turns = mutableListOf<Pair<String, String>>()
        var pendingUser: String? = null
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = (m.optString("role").ifEmpty { m.optString("from") }).lowercase()
            val content = text(if (m.has("content")) m.opt("content") else m.opt("value"))
            when (role) {
                "system" -> if (system == null) system = content
                "user", "human" ->
                    pendingUser = pendingUser?.let { "$it\n\n$content" } ?: content
                "assistant", "gpt", "model", "bot" -> {
                    pendingUser?.let { turns += it to content }
                    pendingUser = null
                }
            }
        }
        return system to turns
    }

    /** Plain strings, or OpenAI-style content arrays like [{"type": "text", "text": "…"}]. */
    private fun text(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is String -> value
        is JSONArray -> (0 until value.length()).joinToString("") { i ->
            when (val part = value.opt(i)) {
                is JSONObject -> part.optString("text")
                is String -> part
                else -> ""
            }
        }
        else -> value.toString()
    }
}
