package app.jsonlbuilder.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonlTest {

    private val pairs = listOf(
        "Hi \"there\"\nnew line" to "Hello! \\ back\tslash",
        "Only input" to "   ",
        "  padded  " to "\n reply \n",
        "" to "",
    )

    @Test
    fun chatFormatIsValidJsonWithSystemPrompt() {
        val lines = Jsonl.lines("Be nice", pairs, ExportOptions(ExportFormat.CHAT))
        assertEquals(2, lines.size)
        val messages = JSONObject(lines[0]).getJSONArray("messages")
        assertEquals(3, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("Be nice", messages.getJSONObject(0).getString("content"))
        assertEquals("Hi \"there\"\nnew line", messages.getJSONObject(1).getString("content"))
        assertEquals("assistant", messages.getJSONObject(2).getString("role"))
        assertEquals("Hello! \\ back\tslash", messages.getJSONObject(2).getString("content"))
        // Trimmed by default.
        assertEquals("padded", JSONObject(lines[1]).getJSONArray("messages").getJSONObject(1).getString("content"))
    }

    @Test
    fun chatFormatOmitsBlankSystemPrompt() {
        val line = Jsonl.lines("  ", pairs.take(1), ExportOptions(ExportFormat.CHAT)).single()
        assertEquals("user", JSONObject(line).getJSONArray("messages").getJSONObject(0).getString("role"))
    }

    @Test
    fun keepsIncompleteButNeverFullyEmptyWhenAsked() {
        val options = ExportOptions(ExportFormat.INPUT_OUTPUT, skipIncomplete = false, trimWhitespace = false)
        val lines = Jsonl.lines("", pairs, options)
        assertEquals(3, lines.size)
        assertEquals("   ", JSONObject(lines[1]).getString("output"))
        assertEquals("\n reply \n", JSONObject(lines[2]).getString("output"))
    }

    @Test
    fun otherFormatsUseTheirKeys() {
        val pc = JSONObject(Jsonl.lines("", pairs.take(1), ExportOptions(ExportFormat.PROMPT_COMPLETION)).single())
        assertEquals(setOf("prompt", "completion"), pc.keys().asSequence().toSet())
        val alpaca = JSONObject(Jsonl.lines("", pairs.take(1), ExportOptions(ExportFormat.ALPACA)).single())
        assertEquals(setOf("instruction", "input", "output"), alpaca.keys().asSequence().toSet())
        assertEquals("", alpaca.getString("input"))
    }

    @Test
    fun escapesControlAndUnicodeCorrectly() {
        val tricky = "emoji 😀 é 中文 \u0001   end"
        val line = Jsonl.quote(tricky)
        assertTrue(line.contains("😀"))
        assertTrue(line.contains("\\u0001"))
        assertEquals(tricky, JSONObject("{\"v\": $line}").getString("v"))
    }

    @Test
    fun buildEndsWithNewlineAndIsEmptyWhenNothingToExport() {
        val text = Jsonl.build("", pairs, ExportOptions())
        assertTrue(text.endsWith("}\n"))
        assertEquals(2, text.trimEnd().lines().size)
        assertEquals("", Jsonl.build("", listOf("" to ""), ExportOptions()))
    }

    @Test
    fun roundTripsEveryFormat() {
        for (format in ExportFormat.entries) {
            val text = Jsonl.build("Sys", pairs, ExportOptions(format))
            val parsed = Jsonl.parse(text)
            assertEquals(format.name, listOf("Hi \"there\"\nnew line" to "Hello! \\ back\tslash", "padded" to "reply"), parsed.pairs)
            assertEquals(format.name, if (format.usesSystemPrompt) "Sys" else "", parsed.systemPrompt)
            assertEquals(0, parsed.skippedLines)
        }
    }

    @Test
    fun parsesMultiTurnShareGptAndBadLines() {
        val text = """
            {"messages": [{"role": "user", "content": "a"}, {"role": "assistant", "content": "b"}, {"role": "user", "content": [{"type": "text", "text": "c"}]}, {"role": "assistant", "content": "d"}]}
            {"conversations": [{"from": "human", "value": "e"}, {"from": "gpt", "value": "f"}]}
            not json
            {"question": "g", "answer": "h"}
            {"something": "else"}
        """.trimIndent()
        val parsed = Jsonl.parse("\uFEFF" + text)
        assertEquals(listOf("a" to "b", "c" to "d", "e" to "f", "g" to "h"), parsed.pairs)
        assertEquals(2, parsed.skippedLines)
    }
}
