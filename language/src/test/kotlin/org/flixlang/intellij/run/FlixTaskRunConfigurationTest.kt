package org.flixlang.intellij.run

import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.wstein.flixplugin.FlixTask

/**
 * What a Flix task configuration stores, and what it refuses.
 *
 * The type is built here rather than looked up: whether it is *registered* is a property of the
 * assembled plugin, which `FlixAssembledPluginTest` checks, and a module fixture has no descriptors
 * loaded to answer it.
 */
class FlixTaskRunConfigurationTest : BasePlatformTestCase() {

    private fun configuration(): FlixTaskRunConfiguration {
        val factory = FlixTaskRunConfigurationType().configurationFactories.single()
        return factory.createTemplateConfiguration(project) as FlixTaskRunConfiguration
    }

    fun testTheTypeOffersExactlyOneFactory() {
        // One type with the subcommand as a field, rather than one type per subcommand: a second
        // factory would mean the New Configuration list had grown a choice nobody decided to add.
        val type = FlixTaskRunConfigurationType()
        assertEquals(FlixTaskRunConfigurationType.ID, type.id)
        assertEquals(1, type.configurationFactories.size)
    }

    fun testTheTaskRoundTripsThroughTheStoredCommand() {
        // Stored as the compiler's own name, not the enum constant's: a renamed constant must not
        // silently change what a saved configuration runs.
        val configuration = configuration()
        configuration.task = FlixTask.BUILD_FATJAR

        assertEquals("build-fatjar", configuration.options.task)
        assertEquals(FlixTask.BUILD_FATJAR, configuration.task)
    }

    fun testEveryTaskCanBeStoredAndReadBack() {
        val configuration = configuration()
        for (task in FlixTask.values()) {
            configuration.task = task
            assertEquals(task, configuration.task)
        }
    }

    fun testAStoredCommandThatIsNoLongerATaskIsRefusedByName() {
        // A configuration saved by an older plugin can name a subcommand that no longer exists.
        // Running it would reach the compiler, which demotes an unknown command to a file argument
        // and reports "Unrecognized file extension" -- naming neither the typo nor the command list.
        val configuration = configuration()
        configuration.options.task = "build-jarr"

        val error = assertThrows(RuntimeConfigurationError::class.java) { configuration.checkConfiguration() }
        assertTrue("the message must name the offending command: ${error.message}",
            error.message!!.contains("build-jarr"))
    }

    fun testRefusesInTheDialogWhenNoCompilerJarCanBeFound() {
        // `java -jar <missing>` fails with a JVM error naming a path, which says nothing about
        // flix.jar or FLIX_JAR. The dialog is where that can still be acted on.
        val configuration = configuration()
        configuration.task = FlixTask.BUILD

        val error = assertThrows(RuntimeConfigurationError::class.java) { configuration.checkConfiguration() }
        val message = error.message.orEmpty()
        assertTrue("the message must name the file to supply: $message", message.contains("flix.jar"))
        assertTrue("the message must name the override: $message", message.contains("FLIX_JAR"))
    }

    private fun <T : Throwable> assertThrows(type: Class<T>, body: () -> Unit): T {
        try {
            body()
        } catch (e: Throwable) {
            if (type.isInstance(e)) return type.cast(e)!!
            throw AssertionError("expected ${type.simpleName}, got ${e::class.java.simpleName}: ${e.message}", e)
        }
        throw AssertionError("expected ${type.simpleName}, but nothing was thrown")
    }
}
