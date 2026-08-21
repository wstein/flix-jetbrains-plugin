package de.wstein.flixplugin.run

import de.wstein.flixplugin.FlixBuildSpec
import de.wstein.flixplugin.FlixJar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/**
 * The agreement between the port the IDE is told to attach to and the port the debuggee listens on.
 *
 * Untested until a review pointed out that the two halves were read by different platform callbacks
 * at different times with nothing tying them together. The failure mode is the reason it is worth a
 * test: a mismatch does not raise anything. The debuggee starts, the IDE connects to a port nothing
 * is listening on, and the session simply never begins — which reads as a broken debugger rather
 * than as two numbers that disagree.
 */
class FlixLaunchTest {

    private val jar = Path.of("/opt/flix/flix-vendor.jar")

    @Test
    fun `the connection port is the port on the program's command line`() {
        // The program's, not the compiler's. The two phases of a debug launch put the agent on the
        // second one, and the connection has to name that one -- see FlixDebuggeeIdentityTest.
        val root = FlixTestProject.withManifest()
        val launch = FlixLaunch(FlixJar.DEFAULT_JAVA, jar, "Main.main", debugPort = 5005, projectRoot = root)

        val onCommandLine = launch.programCommand(FlixBuildSpec.read(root)).single { it.startsWith("-agentlib:jdwp") }
        assertTrue(
            "the agent must carry the port the connection names, got $onCommandLine",
            onCommandLine.endsWith("address=*:5005"),
        )
        assertEquals("5005", launch.remoteConnection!!.debuggerAddress)
    }

    @Test
    fun `a debug launch asks the compiler for debug information`() {
        // --Xdebug is not merely a JDWP switch: without it the compiler emits no line numbers for
        // let, calls, if or statement sequences, so breakpoints on those lines can never bind. It
        // goes on the build phase, which is the phase the compiler runs in.
        assertTrue(FlixLaunch(FlixJar.DEFAULT_JAVA, jar, null, debugPort = 5005).buildCommand.contains("--Xdebug"))
    }

    @Test
    fun `a plain run has no agent, no connection and no debug flag`() {
        val launch = FlixLaunch(FlixJar.DEFAULT_JAVA, jar, "Main.main", debugPort = null)

        assertNull("a Run must not offer the debugger anywhere to attach", launch.remoteConnection)
        assertTrue(launch.command.none { it.startsWith("-agentlib:jdwp") })
        assertTrue("--Xdebug changes the emitted program; a Run must not", "--Xdebug" !in launch.command)
    }

    @Test
    fun `the debuggee listens and the IDE connects, not the other way round`() {
        // RemoteConnection's `server` flag describes the IDE's role. Reversing it makes the IDE
        // listen while the debuggee also listens, and nothing ever connects to anything.
        val connection = FlixLaunch(FlixJar.DEFAULT_JAVA, jar, null, debugPort = 5005).remoteConnection!!
        assertTrue("the IDE must not be the server", !connection.isServerMode)
        assertEquals(FlixLaunch.LOCALHOST, connection.debuggerHostName)
    }

    @Test
    fun `each launch gets its own port`() {
        // The platform reads the port through two separate callbacks; anything that re-derived it
        // rather than storing it would hand out two different numbers. Two launches must also not
        // collide, since two debug sessions can run at once.
        val first = FlixLaunch.of(
            FlixJar.DEFAULT_JAVA, jar, null, null, null, debug = true, inheritedJavaToolOptions = null,
        )
        val second = FlixLaunch.of(
            FlixJar.DEFAULT_JAVA, jar, null, null, null, debug = true, inheritedJavaToolOptions = null,
        )

        assertTrue("a debug launch must allocate a port", first.debugPort != null)
        assertTrue("two concurrent sessions must not share a port", first.debugPort != second.debugPort)
        // ...and the port must be stable across reads, not re-derived on each one.
        assertEquals(first.debugPort.toString(), first.remoteConnection!!.debuggerAddress)
        assertEquals(first.buildCommand, first.buildCommand)
    }

    @Test
    fun `a non-debug launch allocates no port at all`() {
        val launch = FlixLaunch.of(
            FlixJar.DEFAULT_JAVA, jar, null, null, null, debug = false, inheritedJavaToolOptions = null,
        )
        assertNull(launch.debugPort)
    }

    @Test
    fun `on a Run, VM and program options sit on their respective sides of the jar`() {
        val launch = FlixLaunch(
            FlixJar.DEFAULT_JAVA,
            jar,
            "Main.main",
            vmOptions = "-Xmx2g -Dflix.mode=test",
            programParameters = "one \"two words\"",
            debugPort = null,
        )

        assertEquals(listOf("java", "-Xmx2g", "-Dflix.mode=test", "-jar"), launch.command.take(4))
        assertEquals(listOf("one", "two words"), launch.command.takeLast(2))
    }

    @Test
    fun `on a Debug, VM and program options sit on their respective sides of the main class`() {
        val root = FlixTestProject.withManifest()
        val launch = FlixLaunch(
            FlixJar.DEFAULT_JAVA,
            jar,
            "Main.main",
            vmOptions = "-Xmx2g -Dflix.mode=test",
            programParameters = "one \"two words\"",
            debugPort = 5005,
            projectRoot = root,
        )

        val command = launch.programCommand(FlixBuildSpec.read(root))
        assertEquals(
            listOf(
                "/opt/jdk/bin/java",
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005",
                "-Xmx2g",
                "-Dflix.mode=test",
                "-cp",
            ),
            command.take(5),
        )
        assertEquals(listOf("one", "two words"), command.takeLast(2))
    }
}
