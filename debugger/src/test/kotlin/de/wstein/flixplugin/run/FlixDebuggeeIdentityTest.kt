package de.wstein.flixplugin.run

import de.wstein.flixplugin.FlixBuildSpec
import de.wstein.flixplugin.FlixJar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/**
 * **The JVM the agent is on is the JVM the program runs in.**
 *
 * The one invariant a debug session cannot be missing, and the one nothing was asserting. On
 * 12 August 2026 `flix-fork@a282efce0` made `flix run` start the program in a JVM of its own; the
 * plugin went on putting `-agentlib:jdwp` on `flix run`, so for eight days every session attached to
 * the *compiler* while the program ran, unwatched, one process further down.
 *
 * Nothing failed. 16,000 tests were green, the position manager answered every question correctly,
 * and the IDE log showed it being asked about hundreds of classes -- all of them the compiler's.
 * Breakpoints simply never bound.
 *
 * Measured with `scripts/FlixLineProbe.java`, same build, same `--Xdebug`, only the JVM differing:
 *
 * ```
 * agent on `flix run`                     0 classes from Main.flix,  every line "absent"
 * agent on `java -cp … Main`              3 classes from Main.flix,  lines 5-8 "can bind"
 * ```
 *
 * These assertions are that table, restated as a property of the command line: one list of
 * arguments must carry both the agent and the class the user's code is in. The previous design
 * fails every one of them -- it produced `java -agentlib:… -jar flix.jar run --Xdebug`, where the
 * class named on the command line is the compiler's.
 */
class FlixDebuggeeIdentityTest {

    private val compilerJar: Path = Path.of("/opt/flix/flix-vendor.jar")

    private fun debugLaunch(root: Path) = FlixLaunch(
        FlixJar.DEFAULT_JAVA,
        compilerJar,
        entryPoint = null,
        debugPort = 5005,
        projectRoot = root,
    )

    @Test
    fun `the agent and the program's main class are on one command line`() {
        val root = FlixTestProject.withManifest()
        val command = debugLaunch(root).programCommand(FlixBuildSpec.read(root))

        val agent = command.singleOrNull { it.startsWith("-agentlib:jdwp") }
        assertTrue("a debug launch must carry a JDWP agent, got $command", agent != null)
        assertTrue(
            "the class the agent's JVM runs must be the program's, got $command",
            command.contains("Main"),
        )
    }

    @Test
    fun `the debuggee is not the compiler`() {
        // The failure this whole change exists to remove. `-jar <compiler>` means the JVM carrying
        // the agent is the one that *builds* the program -- it never loads a single class of it.
        val root = FlixTestProject.withManifest()
        val command = debugLaunch(root).programCommand(FlixBuildSpec.read(root))

        assertTrue("the debuggee must not be the Flix compiler, got $command", "-jar" !in command)
        assertTrue(compilerJar.toString() !in command)
    }

    @Test
    fun `the agent precedes the classpath`() {
        // Everything from the main class on is the program's own argument list, and a JVM option
        // after `-cp` is either rejected or handed to the program.
        val root = FlixTestProject.withManifest()
        val command = debugLaunch(root).programCommand(FlixBuildSpec.read(root))

        assertTrue(command.indexOfFirst { it.startsWith("-agentlib:jdwp") } < command.indexOf("-cp"))
        assertTrue(command.indexOf("-cp") < command.indexOf("Main"))
    }

    @Test
    fun `the program runs on the java the build recorded`() {
        // Not FlixJar.javaExecutable. That is the JVM the *compiler* runs on; this is the JVM the
        // compiler's output was built for, and starting the program on an older one fails with a
        // class-version error naming neither.
        val root = FlixTestProject.withManifest()
        val command = debugLaunch(root).programCommand(FlixBuildSpec.read(root))

        assertEquals("/opt/jdk/bin/java", command.first())
    }

    @Test
    fun `the build phase carries no agent`() {
        // Phase one is the compiler. An agent here is the bug, exactly as it was.
        val command = debugLaunch(FlixTestProject.withManifest()).buildCommand

        assertTrue("the compiler must not be debugged, got $command", command.none { it.startsWith("-agentlib") })
        assertTrue("phase one must build rather than run", command.contains("build"))
        assertTrue("without --Xdebug the program has no breakpointable lines", command.contains("--Xdebug"))
    }

    @Test
    fun `a build with no entry point is refused rather than launched`() {
        val root = FlixTestProject.withManifest(mainClass = null)
        val failure = runCatching { debugLaunch(root).programCommand(FlixBuildSpec.read(root)) }.exceptionOrNull()

        assertTrue("expected a launch failure naming the missing entry point, got $failure", failure != null)
        assertTrue(failure!!.message!!.contains("entry point"))
    }

    @Test
    fun `a plain Run is still one process and carries no agent`() {
        // The consensus was narrow: only Debug stops going through `flix run`. A Run that started
        // the program itself would be a fourth way to run a Flix program that nothing exercises.
        val command = FlixLaunch(FlixJar.DEFAULT_JAVA, compilerJar, null, debugPort = null).command

        assertTrue(command.contains("-jar"))
        assertTrue(command.contains("run"))
        assertTrue(command.none { it.startsWith("-agentlib") })
    }
}
