package org.flixlang.intellij.run

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Reads one line of `flix test --events-json`.
 *
 * ## Why a line that is not an event is not an error
 *
 * Because the stream carries three kinds of line and only one of them is ours. A JVM warning, a
 * `-Xlog` line, anything written before the runner installed its quarantine -- none of these are
 * events and none are malformed. [parse] answers `null` for them and the caller prints them
 * verbatim, which is the same judgement the compiler's own forked runner reached after getting it
 * wrong: its first version reported an unparseable line as a *failed test named `<runner>`*, which
 * invents a test that does not exist and files it in the user's test tree.
 *
 * The same answer covers well-formed JSON that is not one of ours, and an event object missing the
 * `name` every test event carries. Both are "not something this understands", and guessing at
 * either would put a fiction in the tree.
 *
 * ## Why the DOM rather than `@Serializable`
 *
 * `kotlinx.serialization`'s generated decoders need its compiler plugin; the `JsonElement` API
 * needs only the runtime library, which the platform already ships as a top-level jar in `lib/`. So
 * this adds nothing to the plugin's own distribution. It also means an unknown field is ignored
 * rather than fatal, which matters for a format the compiler may extend.
 */
object FlixTestEventParser {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The event [line] describes, or `null` if it is not one.
     *
     * Never throws: every malformed input is "not an event", which the caller already has a
     * correct behaviour for.
     */
    fun parse(line: String): FlixTestEvent? {
        val root = try {
            json.parseToJsonElement(line.trim()) as? JsonObject ?: return null
        } catch (_: Exception) {
            // Not JSON at all. The overwhelmingly common case, and not a failure.
            return null
        }

        return when (root.string("event")) {
            "start" -> FlixTestEvent.Started(testsIn(root["tests"]))
            "before" -> root.testRef()?.let(FlixTestEvent::Before)
            "passed" -> root.testRef()?.let { FlixTestEvent.Passed(it, root.nanos()) }
            "failed" -> root.testRef()?.let { FlixTestEvent.Failed(it, root.nanos(), stringsIn(root["output"])) }
            "skipped" -> root.testRef()?.let(FlixTestEvent::Skipped)
            "finished" -> FlixTestEvent.Finished(root.nanos())
            "output" -> FlixTestEvent.Output(root.string("line").orEmpty())
            else -> null
        }
    }

    private fun testsIn(element: JsonElement?): List<FlixTestRef> =
        (element as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.testRef() }

    private fun stringsIn(element: JsonElement?): List<String> =
        (element as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

    /**
     * The test this object names, or `null` if it names none.
     *
     * The location is all-or-nothing on purpose: `JsonTestSink.idFields` writes the five fields
     * together or omits them together, so a partial set means the format changed rather than that
     * a test is half-located, and a half-read location would point somewhere arbitrary.
     */
    private fun JsonObject.testRef(): FlixTestRef? {
        val name = string("name") ?: return null
        val file = string("file")
        val location = if (file == null) null else {
            val startLine = int("startLine")
            val startCol = int("startCol")
            val endLine = int("endLine")
            val endCol = int("endCol")
            if (startLine == null || startCol == null || endLine == null || endCol == null) null
            else FlixTestLocation(file, startLine, startCol, endLine, endCol)
        }
        return FlixTestRef(name, location, skip = bool("skip") ?: false)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    /** Nanoseconds, defaulting to zero: a duration the runner did not send is not a failure. */
    private fun JsonObject.nanos(): Long = (this["nanos"] as? JsonPrimitive)?.longOrNull ?: 0L

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
