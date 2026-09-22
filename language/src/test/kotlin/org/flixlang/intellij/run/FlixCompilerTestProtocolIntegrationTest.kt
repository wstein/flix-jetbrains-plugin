package org.flixlang.intellij.run

import org.flixlang.intellij.run.FlixTestEvent.Output
import org.flixlang.intellij.run.FlixTestEvent.Passed
import org.flixlang.intellij.run.FlixTestEvent.Started
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Runs the selected compiler jar, then feeds its real stdout to the plugin parser.
 *
 * Fixture-only parser tests protect the reader from regressions, but cannot prove that the
 * compiler currently selected by the plugin still accepts `--events-json` or writes that wire
 * format. Set `FLIX_JAR` to opt into this cross-repository compatibility gate; ordinary plugin
 * builds skip it because a compiler checkout is not a plugin build dependency.
 */
class FlixCompilerTestProtocolIntegrationTest {

    @Test
    fun compilerAndPluginAgreeOnFilteredTestEvents() {
        val jar = compilerJarOrSkip()
        // `FlixTaskRunConfiguration.commandFor` never puts a source file on the command line --
        // it always runs the project the working directory names. A file argument takes the
        // compiler down `Main.scala`'s other `test` path, which does not apply `--filter` at all
        // (`Tester.run(Nil, ...)`), so a positional file here would pass while proving nothing
        // about the invocation the plugin actually makes.
        val projectDir = Files.createTempDirectory("flix-test-events-")
        try {
            Files.writeString(
                projectDir.resolve("flix.toml"),
                """
                [package]
                name        = "flix-test-events"
                description = "scratch fixture for the protocol compatibility gate"
                version     = "0.1.0"
                flix        = "0.75.1"
                license     = "BSD-3-Clause"
                authors     = ["scratch"]
                """.trimIndent(),
                StandardCharsets.UTF_8,
            )
            Files.createDirectory(projectDir.resolve("src"))
            Files.writeString(
                projectDir.resolve("src/Protocol.flix"),
                """
                mod Protocol {
                    @Test
                    def selected(): Unit \ IO = println("héllo from compiler")

                    @Test
                    def excluded(): Unit \ Assert = Assert.fail("filter did not apply")
                }
                """.trimIndent(),
                StandardCharsets.UTF_8,
            )

            val process = ProcessBuilder(
                javaExecutable(),
                "-jar",
                jar.toString(),
                "test",
                "--events-json",
                "--filter",
                "Protocol\\.selected",
            ).directory(projectDir.toFile()).redirectErrorStream(true).start()
            val lines = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readLines()

            assertTrue("compiler test process timed out", process.waitFor(60, TimeUnit.SECONDS))
            assertEquals("compiler output:\n${lines.joinToString("\n")}", 0, process.exitValue())

            val events = lines.mapNotNull(FlixTestEventParser::parse)
            val started = events.filterIsInstance<Started>().single()
            assertEquals(listOf("Protocol.selected"), started.tests.map { it.name })
            assertEquals(listOf("Protocol.selected"), events.filterIsInstance<Passed>().map { it.test.name })
            assertEquals(listOf("héllo from compiler"), events.filterIsInstance<Output>().map { it.line })
            assertFalse("the excluded test ran: $events", events.any { eventNames(it).contains("Protocol.excluded") })
        } finally {
            projectDir.toFile().deleteRecursively()
        }
    }

    private fun compilerJarOrSkip(): Path {
        val configured = System.getenv("FLIX_JAR")
            ?.takeIf(String::isNotBlank)
            ?: System.getProperty("flixJar")?.takeIf(String::isNotBlank)
        assumeTrue("set FLIX_JAR or -DflixJar to run the compiler compatibility gate", configured != null)
        val jar = Path.of(configured!!).toAbsolutePath().normalize()
        assertTrue("compiler jar does not exist: $jar", Files.isRegularFile(jar))
        return jar
    }

    private fun javaExecutable(): String =
        Path.of(System.getProperty("java.home"), "bin", if (isWindows()) "java.exe" else "java").toString()

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private fun eventNames(event: FlixTestEvent): List<String> = when (event) {
        is Started -> event.tests.map { it.name }
        is FlixTestEvent.Before -> listOf(event.test.name)
        is Passed -> listOf(event.test.name)
        is FlixTestEvent.Failed -> listOf(event.test.name)
        is FlixTestEvent.Skipped -> listOf(event.test.name)
        is FlixTestEvent.Finished, is FlixTestEvent.ProtocolMismatch, is Output -> emptyList()
    }
}
