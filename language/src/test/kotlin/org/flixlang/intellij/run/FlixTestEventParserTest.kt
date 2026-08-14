package org.flixlang.intellij.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire format of `flix test --events-json`, as the compiler actually writes it.
 *
 * `testEvents/mixed-run.jsonl` was **captured from a real run** of a project with a passing test, a
 * failing one, a `@Skip`ped one and one that prints, against a jar built from the fork. Only the
 * machine-specific directory was replaced, with `/projects/fixture`. That matters: a fixture typed
 * out by hand pins what someone believed the format was, and the two have already differed here --
 * the escapes, the interleaving and the absolute paths below were all discovered from the capture
 * rather than predicted.
 */
class FlixTestEventParserTest {

    @Test
    fun `parses every line of a real run`() {
        val events = fixture().map(FlixTestEventParser::parse)

        assertTrue("a captured line was not recognised: ${events.indexOf(null)}", events.none { it == null })
        assertEquals(13, events.size)
    }

    @Test
    fun `a start event announces every test, with its skip flag`() {
        val started = fixture().firstNotNullOf { FlixTestEventParser.parse(it) as? FlixTestEvent.Started }

        assertEquals(
            listOf("testFails", "testPasses", "testPrints", "testSkipped"),
            started.tests.map { it.name },
        )
        assertEquals(listOf("testSkipped"), started.tests.filter { it.skip }.map { it.name })
    }

    @Test
    fun `a test event carries its own location, so no state is needed to place it`() {
        val before = fixture().firstNotNullOf { FlixTestEventParser.parse(it) as? FlixTestEvent.Before }

        assertEquals("testFails", before.test.name)
        assertEquals(
            FlixTestLocation("/projects/fixture/test/TestMain.flix", 5, 5, 5, 14),
            before.test.location,
        )
    }

    @Test
    fun `a failure carries its duration and the runner's own account of it`() {
        val failed = fixture().firstNotNullOf { FlixTestEventParser.parse(it) as? FlixTestEvent.Failed }

        assertEquals("testFails", failed.test.name)
        assertTrue("a failure reported no duration", failed.nanos > 0)
        assertTrue(
            "the failure's first line should be the assertion, was: ${failed.output.firstOrNull()}",
            failed.output.first().contains("Assertion failed"),
        )
        assertTrue("the stack trace was dropped", failed.output.any { it.contains("dev.flix.gen") })
    }

    /**
     * The reason [FlixTestEvent.Skipped] cannot assume a start has happened.
     *
     * `Tester.runTest` returns before emitting `Before` for a skipped test. Asserting it from the
     * captured stream rather than from reading that code means the assumption is checked against
     * the compiler, not against a memory of it.
     */
    @Test
    fun `a skipped test is never preceded by a before`() {
        val events = fixture().mapNotNull(FlixTestEventParser::parse)
        val skipped = events.filterIsInstance<FlixTestEvent.Skipped>().single()

        assertEquals("testSkipped", skipped.test.name)
        assertTrue(
            "a skipped test emitted a Before, so synthesising one would double-start it",
            events.filterIsInstance<FlixTestEvent.Before>().none { it.test.name == skipped.test.name },
        )
    }

    /**
     * Why output is not attributed to whichever test is running.
     *
     * In this capture `héllo from a test` -- written by `testPrints` -- arrives **after
     * `testFails` finished and before `testPrints` starts**. `Tester` runs tests on one thread and
     * reports them from another, so the interleaving is approximate by construction. Any rule that
     * attached output to the test in flight would file this line under the wrong test, or under no
     * test at all.
     */
    @Test
    fun `output is not attributable to the test in flight`() {
        val events = fixture().mapNotNull(FlixTestEventParser::parse)
        val printed = events.indexOfFirst { it is FlixTestEvent.Output && it.line.contains("héllo") }
        val printerStarted = events.indexOfFirst { it is FlixTestEvent.Before && it.test.name == "testPrints" }

        assertTrue("the fixture no longer contains the printed line", printed >= 0)
        assertTrue(
            "output now arrives inside its own test, so the attribution rule could be revisited",
            printed < printerStarted,
        )
    }

    @Test
    fun `output survives a round trip through UTF-8`() {
        val events = fixture().mapNotNull(FlixTestEventParser::parse)

        assertTrue(
            "the non-ASCII line was mangled: ${events.filterIsInstance<FlixTestEvent.Output>().map { it.line }}",
            events.filterIsInstance<FlixTestEvent.Output>().any { it.line == "héllo from a test" },
        )
    }

    @Test
    fun `a line that is not an event is not one`() {
        // Everything a JVM writes onto the same stream. None of it is malformed; none of it is ours.
        assertNull(FlixTestEventParser.parse("Tester Error"))
        assertNull(FlixTestEventParser.parse("OpenJDK 64-Bit Server VM warning: some warning"))
        assertNull(FlixTestEventParser.parse(""))
        assertNull(FlixTestEventParser.parse("{ not json"))
        // Well-formed JSON that is not one of ours.
        assertNull(FlixTestEventParser.parse("""{"event":"somethingElse","name":"x"}"""))
        assertNull(FlixTestEventParser.parse("""{"hello":"world"}"""))
        // Ours in shape, but naming no test -- which is the one thing every test event does.
        assertNull(FlixTestEventParser.parse("""{"event":"passed","nanos":1}"""))
    }

    @Test
    fun `a test with no location is still a test`() {
        val event = FlixTestEventParser.parse("""{"event":"before","name":"testAnonymous"}""")

        assertEquals(FlixTestEvent.Before(FlixTestRef("testAnonymous", location = null)), event)
    }

    /**
     * A half-written location is refused rather than half-read.
     *
     * `JsonTestSink.idFields` writes the five fields together or omits them together, so a partial
     * set means the format moved. Reading what is there would point the row at column zero of some
     * line, which is worse than not linking it at all.
     */
    @Test
    fun `a partial location is no location`() {
        val event = FlixTestEventParser.parse("""{"event":"before","name":"t","file":"A.flix","startLine":3}""")

        assertEquals(FlixTestEvent.Before(FlixTestRef("t", location = null)), event)
    }

    private fun fixture(): List<String> =
        checkNotNull(javaClass.getResourceAsStream("/testEvents/mixed-run.jsonl")) {
            "the captured run is missing from test resources"
        }.bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() }
}
