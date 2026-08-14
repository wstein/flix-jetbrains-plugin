package org.flixlang.intellij.run

import com.intellij.execution.testframework.sm.ServiceMessageBuilder
import java.nio.file.Path

/**
 * Turns a [FlixTestEvent] into the service messages the platform's test runner reads.
 *
 * ## Why text rather than driving the processor
 *
 * `OutputToGeneralTestEventsConverter` can be made to call `GeneralTestEventsProcessor` directly,
 * with `TestStartedEvent` and friends built by hand. Producing the messages as *text* and handing
 * them back to the platform's own parser instead means the event objects, their constructors and
 * their defaults stay the platform's business, so this cannot drift when they change -- and it
 * makes the whole mapping a pure function, testable without a fixture, an IDE or a process.
 *
 * ## The sequences, and why they are what they are
 *
 * Each was read off `GeneralToSMTRunnerEventsConvertor` rather than assumed:
 *
 * - **A skipped test is started first.** `onTestIgnored` for a test it has not seen logs
 *   *"Test wasn't started!"* and synthesises a start with **no location**, so the row loses its
 *   link. `Tester.runTest` returns before emitting `Before` for a skipped test, so this is the
 *   only place that start can come from.
 * - **A skipped test is finished afterwards.** `onTestIgnored` marks the proxy and fires, but does
 *   not close it; without a finish the row stays spinning for the rest of the run.
 * - **A failed test is finished afterwards** for the same reason: `testFailed` records the failure
 *   on a test that is still open.
 */
object FlixTestServiceMessages {

    /**
     * The messages [event] becomes, in order, each already terminated by a newline.
     *
     * Empty for an event the tree does not render: [FlixTestEvent.Finished] is one, because the
     * suite is closed by the process ending rather than by a message.
     *
     * @param workingDirectory what a relative path in a location is relative to -- the compiler
     *   prints the path as it was given, and the task runs in the project directory. Absolute paths
     *   are unaffected, which `Path.resolve` already gets right.
     */
    fun messagesFor(event: FlixTestEvent, workingDirectory: Path): List<String> = when (event) {
        is FlixTestEvent.Started ->
            listOf(ServiceMessageBuilder("testCount").addAttribute("count", event.tests.size.toString()).line())

        is FlixTestEvent.Before ->
            listOf(started(event.test, workingDirectory))

        is FlixTestEvent.Passed ->
            listOf(finished(event.test, event.nanos))

        is FlixTestEvent.Failed -> listOf(
            ServiceMessageBuilder.testFailed(event.test.name)
                // Stripped, because this becomes the failure's title in the tree, and a tree row is
                // not a console: it renders the escapes as text. The compiler colours its assertion
                // messages, so an unstripped title reads `[38;2;220;105;105mAssertion failed:`.
                .addAttribute("message", FlixCompilerOutputFilter.strip(event.output.firstOrNull().orEmpty()))
                // Not stripped. This goes to the test's own output pane, which is a console and
                // decodes them -- so keeping them keeps the compiler's colouring, which is most of
                // what makes a stack trace readable.
                .addAttribute("details", event.output.drop(1).joinToString("\n"))
                .line(),
            finished(event.test, event.nanos),
        )

        is FlixTestEvent.Skipped -> listOf(
            started(event.test, workingDirectory),
            ServiceMessageBuilder.testIgnored(event.test.name).line(),
            finished(event.test, nanos = 0),
        )

        // The process ending closes the suite; a message here would close it twice.
        is FlixTestEvent.Finished -> emptyList()

        // Not a message: it belongs to no test, so it goes to the console as ordinary output. The
        // converter does that directly -- see FlixTestEventConverter.
        is FlixTestEvent.Output -> emptyList()
    }

    private fun started(test: FlixTestRef, workingDirectory: Path): String {
        val builder = ServiceMessageBuilder.testStarted(test.name)
        locationHint(test, workingDirectory)?.let { builder.addAttribute("locationHint", it) }
        return builder.line()
    }

    /** `duration` is milliseconds, which is what `TestFinishedEvent` carries. */
    private fun finished(test: FlixTestRef, nanos: Long): String =
        ServiceMessageBuilder.testFinished(test.name)
            .addAttribute("duration", (nanos / 1_000_000).toString())
            .line()

    /**
     * `file://<path>:<line>:<column>`, or `null` for a test that carries no location.
     *
     * The line and column are passed through **unchanged**. `FileUrlProvider.createLocationFor`
     * does `getLineStartOffset(line - 1)` and `max(column - 1, 0)`, so it expects the 1-based
     * values `SourceLocation` already reports; subtracting here would move every result one line
     * up and one column left.
     */
    internal fun locationHint(test: FlixTestRef, workingDirectory: Path): String? {
        val location = test.location ?: return null
        val path = workingDirectory.resolve(location.file).normalize()
        return "file://$path:${location.startLine}:${location.startCol}"
    }

    private fun ServiceMessageBuilder.line(): String = "$this\n"
}
