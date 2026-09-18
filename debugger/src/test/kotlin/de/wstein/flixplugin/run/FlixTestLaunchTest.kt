package de.wstein.flixplugin.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlixTestLaunchTest {

    @Test
    fun `debug attaches to the compiler JVM that executes the tests`() {
        val launch = FlixTestLaunch.debug(
            listOf(
                "/jdk/bin/java",
                "-Xmx2g",
                "-jar",
                "/project/flix.jar",
                "test",
                "--events-json",
                "--filter",
                "\\QSuite.selected\\E",
                "--",
                "program-argument",
            ),
            port = 5005,
            inheritedJavaToolOptions = null,
        )

        assertEquals("/jdk/bin/java", launch.command.first())
        assertEquals(
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005",
            launch.command[1],
        )
        val task = launch.command.indexOf("test")
        val separator = launch.command.indexOf("--")
        assertTrue("--Xdebug must be compiler input: ${launch.command}", launch.command.indexOf("--Xdebug") in (task + 1)..<separator)
        assertEquals(1, launch.command.count { it == "--Xdebug" })
        assertEquals(listOf("program-argument"), launch.command.drop(separator + 1))
        assertFalse("the debug command must not fork through `flix run`", launch.command.contains("run"))
        assertEquals("5005", launch.remoteConnection.address)
    }

    @Test
    fun `compiler debug flags are normalized without touching program arguments`() {
        val launch = FlixTestLaunch.debug(
            listOf(
                "java",
                "-jar",
                "flix.jar",
                "test",
                "--Xdebug",
                "--filter",
                "selected",
                "--Xdebug",
                "--",
                "--Xdebug",
            ),
            5005,
            null,
        )
        val task = launch.command.indexOf("test")
        val separator = launch.command.indexOf("--")

        assertEquals(1, launch.command.subList(task + 1, separator).count { it == "--Xdebug" })
        assertEquals(listOf("--Xdebug"), launch.command.drop(separator + 1))
    }

    @Test(expected = IllegalStateException::class)
    fun `an inherited debug agent is rejected before launch`() {
        FlixTestLaunch.debug(listOf("java", "-jar", "flix.jar", "test"), 5005, "-agentlib:jdwp=address=5006")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a configured JVM debug agent is rejected before launch`() {
        FlixTestLaunch.debug(
            listOf("java", "-agentlib:jdwp=address=5006", "-jar", "flix.jar", "test"),
            5005,
            null,
        )
    }
}
