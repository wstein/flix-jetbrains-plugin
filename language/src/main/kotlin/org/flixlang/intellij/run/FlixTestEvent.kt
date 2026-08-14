package org.flixlang.intellij.run

/**
 * One line of `flix test --events-json`, as a type.
 *
 * ## Why the compiler is asked for JSON rather than parsed as text
 *
 * `flix test` renders to a terminal, and a rendering is not a protocol: the counts are aligned for
 * a human, a failure's output is interleaved with its heading, and colour escapes run through all
 * of it. Scraping that would make this plugin's test tree depend on the compiler's console
 * formatting, which is free to change and has no reason to consider a reader.
 *
 * `--events-json` is the compiler's own [Tester] events, one JSON object per line, written by
 * `JsonTestSink` in the same process that decides what a test outcome *is*. `flix test` and this
 * tree therefore cannot come to disagree about whether a test passed -- they are two renderings of
 * one runner, which is the property the sink design exists to protect.
 *
 * ## Why every event repeats the location
 *
 * `JsonTestSink.idFields` writes name *and* location onto every event that names a test, so a
 * reader needs no state to make a result clickable. That is why [FlixTestEventParser] is a pure
 * function on one line, and why nothing here has to remember which test is open.
 *
 * The location fields are absent, all of them together, for a test whose symbol carries none.
 */
sealed interface FlixTestEvent {

    /** The run is about to begin, and these are the tests it will cover. */
    data class Started(val tests: List<FlixTestRef>) : FlixTestEvent

    /** A test is about to run. Not emitted for a skipped test -- see [Skipped]. */
    data class Before(val test: FlixTestRef) : FlixTestEvent

    data class Passed(val test: FlixTestRef, val nanos: Long) : FlixTestEvent

    /**
     * @param output what the test wrote, plus the reason it failed as the runner phrased it
     *   (`"Assertion Error"`, `"Std Err Output"`, or a formatted stack trace).
     */
    data class Failed(val test: FlixTestRef, val nanos: Long, val output: List<String>) : FlixTestEvent

    /**
     * A `@Skip`ped test, which **never produced a [Before]**.
     *
     * `Tester.runTest` returns as soon as it sees the skip flag, so this is the only event that
     * test emits. A reader that assumed a start had already happened would report a skip against
     * nothing.
     */
    data class Skipped(val test: FlixTestRef) : FlixTestEvent

    /** The run is over. Carries the whole run's duration, not the last test's. */
    data class Finished(val nanos: Long) : FlixTestEvent

    /**
     * Something the program under test wrote outside any test's own captured output.
     *
     * The runner quarantines `System.out` and reports it this way so that a `println` cannot land
     * between two JSON objects and end the conversation. It is deliberately *not* attributed to a
     * test: it arrives while some test is running, but a failing test's own output is already
     * carried on [Failed], so attaching this to whichever test is in flight would be a guess
     * presented as a fact.
     */
    data class Output(val line: String) : FlixTestEvent
}

/**
 * A test, as an event names it.
 *
 * @param skip carried only by [FlixTestEvent.Started], which is the one event that describes tests
 *   rather than reporting on them; the per-test events say the same thing by being a
 *   [FlixTestEvent.Skipped].
 */
data class FlixTestRef(
    val name: String,
    val location: FlixTestLocation?,
    val skip: Boolean = false,
)

/**
 * Where a test is written.
 *
 * Lines and columns are **1-based**, as `SourceLocation` reports them, and are passed to the
 * platform unchanged: `FileUrlProvider.createLocationFor` does `getLineStartOffset(line - 1)` and
 * `max(column - 1, 0)`, so it expects exactly this base. Converting here would move every result
 * one line up.
 *
 * @param file the path as the compiler was given it, so it may be relative to the project.
 */
data class FlixTestLocation(
    val file: String,
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
)
