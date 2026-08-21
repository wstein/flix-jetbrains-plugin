package org.flixlang.intellij.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.wstein.flixplugin.FlixTask
import org.flixlang.intellij.run.FlixTaskRunConfiguration
import org.flixlang.intellij.run.FlixTaskRunConfigurationType
import java.nio.file.Path

/**
 * The extra arguments every Flix process this plugin starts is given.
 *
 * Two fields and one splitting rule, but the rule is the whole point: a JVM option after `-jar` is
 * the compiler's input, and a compiler option before the subcommand is treated as global and stops
 * command parsing. What these tests check is that each argument lands in the half it belongs to.
 */
class FlixSettingsTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // The project service outlives a single test method, so a value one test sets is still
        // there for the next one -- which made the ordering test fail against arguments it never
        // configured.
        settings().extraJvmArgs = ""
        settings().extraFlixArgs = ""
    }

    private fun settings() = FlixSettings.getInstance(project)

    private fun configuration(): FlixTaskRunConfiguration {
        val factory = FlixTaskRunConfigurationType().configurationFactories.single()
        return factory.createTemplateConfiguration(project) as FlixTaskRunConfiguration
    }

    fun testNothingIsPassedUntilSomethingIsSet() {
        assertEquals(emptyList<String>(), settings().jvmArguments)
        assertEquals(emptyList<String>(), settings().flixArguments)
    }

    fun testArgumentsAreSplitTheWayACommandLineSplitsThem() {
        // Not `split(" ")`: a path with a space in it is one argument, and a user who quoted it
        // meant that.
        settings().extraJvmArgs = """-Xmx2g -Dflix.home="/opt/my flix""""
        assertEquals(listOf("-Xmx2g", "-Dflix.home=/opt/my flix"), settings().jvmArguments)
    }

    fun testASettingSurvivesBeingReadBack() {
        settings().extraFlixArgs = "--github-token abc"
        assertEquals(listOf("--github-token", "abc"), settings().flixArguments)
        assertEquals("--github-token abc", settings().extraFlixArgs)
    }

    fun testEachArgumentLandsInTheHalfOfTheCommandItBelongsTo() {
        settings().extraJvmArgs = "-Xmx2g"
        settings().extraFlixArgs = "--github-token abc"

        val configuration = configuration()
        configuration.task = FlixTask.BUILD

        assertEquals(
            listOf("java", "-Xmx2g", "-jar", "/flix.jar", "build", "--github-token", "abc"),
            configuration.commandFor(Path.of("/flix.jar")),
        )
    }

    fun testTheConfigurationsOwnArgumentsComeAfterTheProjectWideOnes() {
        // So a flag set on one task overrides the same flag set for every task. Reversing them
        // would make the narrower setting the one that loses.
        settings().extraFlixArgs = "--github-token project"

        val configuration = configuration()
        configuration.task = FlixTask.TEST
        configuration.arguments = "--github-token thisTask"

        // `--events-json` last, after both: it is not a preference competing with them but the
        // format the test console reads, so nothing a user sets may displace it.
        assertEquals(
            listOf(
                "java", "-jar", "/flix.jar", "test",
                "--github-token", "project", "--github-token", "thisTask", "--events-json",
            ),
            configuration.commandFor(Path.of("/flix.jar")),
        )
    }
}
