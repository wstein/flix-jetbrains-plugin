package org.flixlang.intellij.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/**
 * What each event becomes, and why the sequences are the shape they are.
 *
 * Every sequence here was read off `GeneralToSMTRunnerEventsConvertor` in the shipped platform jar
 * rather than assumed, and each test names the failure that the shape prevents.
 */
class FlixTestServiceMessagesTest {

    private val project = Path.of("/projects/fixture")

    private val located = FlixTestRef(
        "testFails",
        FlixTestLocation("/projects/fixture/test/TestMain.flix", 5, 5, 5, 14),
    )

    @Test
    fun `a start becomes a count the tree can size itself with`() {
        val messages = messagesFor(FlixTestEvent.Started(listOf(located, located)))

        assertEquals(1, messages.size)
        assertTrue(messages.single(), messages.single().startsWith("##teamcity[testCount "))
        assertTrue(messages.single(), messages.single().contains("count='2'"))
    }

    /**
     * The line and column are passed through unchanged.
     *
     * `FileUrlProvider.createLocationFor` does `getLineStartOffset(line - 1)` and
     * `max(column - 1, 0)`, so it expects the 1-based values `SourceLocation` reports. Converting
     * here would put every result one line up -- the kind of off-by-one that looks plausible in a
     * screenshot and is wrong on every row.
     */
    @Test
    fun `a location hint is 1-based, as the platform expects`() {
        assertEquals(
            "file:///projects/fixture/test/TestMain.flix:5:5",
            FlixTestServiceMessages.locationHint(located, project),
        )
    }

    @Test
    fun `a relative path is resolved against the project`() {
        val relative = FlixTestRef("t", FlixTestLocation("test/TestMain.flix", 9, 5, 9, 16))

        assertEquals(
            "file:///projects/fixture/test/TestMain.flix:9:5",
            FlixTestServiceMessages.locationHint(relative, project),
        )
    }

    @Test
    fun `a test with no location gets no hint rather than a wrong one`() {
        assertEquals(null, FlixTestServiceMessages.locationHint(FlixTestRef("t", null), project))
    }

    /**
     * A skipped test is started, ignored, then finished -- in that order, and all three are needed.
     *
     * Without the start, `onTestIgnored` logs *"Test wasn't started!"* and synthesises one with a
     * null location, so the row loses its link. Without the finish, the proxy is marked but never
     * closed, and the row spins for the rest of the run.
     */
    @Test
    fun `a skip is started, ignored and finished`() {
        val messages = messagesFor(FlixTestEvent.Skipped(located))

        assertEquals(3, messages.size)
        assertTrue(messages[0], messages[0].startsWith("##teamcity[testStarted "))
        assertTrue("the synthesised start must keep the link", messages[0].contains("locationHint="))
        assertTrue(messages[1], messages[1].startsWith("##teamcity[testIgnored "))
        assertTrue(messages[2], messages[2].startsWith("##teamcity[testFinished "))
    }

    /** `testFailed` records a failure on a test that is still open, so it needs the finish too. */
    @Test
    fun `a failure is reported and then finished`() {
        val messages = messagesFor(FlixTestEvent.Failed(located, nanos = 5_831_084, output = listOf("boom", "at x")))

        assertEquals(2, messages.size)
        assertTrue(messages[0], messages[0].startsWith("##teamcity[testFailed "))
        assertTrue(messages[1], messages[1].startsWith("##teamcity[testFinished "))
    }

    /** Milliseconds, which is what `TestFinishedEvent` carries. 5,831,084ns is 5ms, not 5,831,084. */
    @Test
    fun `a duration is reported in milliseconds`() {
        val messages = messagesFor(FlixTestEvent.Passed(located, nanos = 5_831_084))

        assertTrue(messages.single(), messages.single().contains("duration='5'"))
    }

    /**
     * The failure title is stripped of colour; the detail is not.
     *
     * The title is rendered by the tree, which shows an escape as text; the detail goes to the
     * test's output pane, which is a console and decodes them. Stripping both would throw away the
     * compiler's colouring of a stack trace, and stripping neither puts `[38;2;220;105;105m` in
     * front of every failure name.
     */
    @Test
    fun `colour is stripped from the title and kept in the detail`() {
        val coloured = "[38;2;220;105;105mAssertion failed: [0mexpected true"
        val messages = messagesFor(FlixTestEvent.Failed(located, nanos = 1, output = listOf(coloured, coloured)))

        val message = messages[0].substringAfter("message='").substringBefore("'")
        val details = messages[0].substringAfter("details='").substringBefore("'")

        assertEquals("Assertion failed: expected true", message)
        // `[` is escaped as `|[` on the wire, so this is the escape sequence surviving intact.
        assertTrue("the detail lost its colouring: $details", details.contains("|[38;2;220;105;105m"))
        assertFalse("the title kept its colouring: $message", message.contains("38;2;220"))
    }

    /** The process ending closes the suite; a message here would close it twice. */
    @Test
    fun `a finish produces nothing`() {
        assertEquals(emptyList<String>(), messagesFor(FlixTestEvent.Finished(nanos = 13_008_500)))
    }

    /**
     * Output is not a service message.
     *
     * `testStdOut` needs a test name, and the only one available would be whichever test happens to
     * be running -- which the captured stream shows is the wrong one. The converter sends it to the
     * console instead.
     */
    @Test
    fun `output produces no message`() {
        assertEquals(emptyList<String>(), messagesFor(FlixTestEvent.Output("héllo from a test")))
    }

    /**
     * A real run produces a well-formed sequence.
     *
     * The unit tests above each pin one event's shape. This pins the property that matters to a
     * user: after a whole run, no row is left spinning. An unclosed test proxy is the failure that
     * the synthesised starts and the trailing finishes exist to prevent, and it is invisible in a
     * per-event assertion because it is a property of the sequence.
     */
    @Test
    fun `every test a real run starts is also finished`() {
        val messages = fixture().mapNotNull(FlixTestEventParser::parse)
            .flatMap { FlixTestServiceMessages.messagesFor(it, project) }

        val started = messages.count { it.startsWith("##teamcity[testStarted ") }
        val finished = messages.count { it.startsWith("##teamcity[testFinished ") }

        assertEquals("a row was left open", started, finished)
        // Four tests, and the skipped one is started here rather than by the runner.
        assertEquals(4, started)
        assertEquals(1, messages.count { it.startsWith("##teamcity[testIgnored ") })
        assertEquals(1, messages.count { it.startsWith("##teamcity[testFailed ") })
    }

    private fun fixture(): List<String> =
        checkNotNull(javaClass.getResourceAsStream("/testEvents/mixed-run.jsonl")) {
            "the captured run is missing from test resources"
        }.bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() }

    private fun messagesFor(event: FlixTestEvent): List<String> =
        FlixTestServiceMessages.messagesFor(event, project).map { it.trimEnd('\n') }
}
