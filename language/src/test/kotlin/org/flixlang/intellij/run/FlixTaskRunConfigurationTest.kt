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

    fun testTheTestTaskAsksTheCompilerForEvents() {
        // Without the flag `flix test` renders a terminal, and the test tree -- which reads JSON --
        // shows nothing while the console fills with the rendering it cannot parse.
        val configuration = configuration()
        configuration.task = FlixTask.TEST

        val command = configuration.commandFor(java.nio.file.Path.of("/tmp/flix.jar"))
        assertTrue(
            "the test task must ask for events: $command",
            command.contains(FlixTaskRunConfiguration.EVENTS_JSON),
        )
        // After the subcommand, like every other option: before it, the compiler treats an option as
        // global and stops parsing the command altogether.
        assertTrue(
            "the flag must follow the subcommand: $command",
            command.indexOf(FlixTaskRunConfiguration.EVENTS_JSON) > command.indexOf(FlixTask.TEST.command()),
        )
    }

    fun testNoOtherTaskIsGivenTheEventsFlag() {
        // `flix build --events-json` is not a command. The flag describes how the *test* runner
        // reports, so a task that reports nothing must not carry it.
        val configuration = configuration()
        for (task in FlixTask.values().filter { it != FlixTask.TEST }) {
            configuration.task = task
            val command = configuration.commandFor(java.nio.file.Path.of("/tmp/flix.jar"))
            assertFalse(
                "${task.command()} was given the test runner's flag: $command",
                command.contains(FlixTaskRunConfiguration.EVENTS_JSON),
            )
        }
    }

    fun testTheRunTestsLensHandlerRunsTheTestTask() {
        // The lens says "Run Tests"; the action behind it has to be the test task and not, say, the
        // one that happens to be first in the enum.
        assertEquals(FlixTask.TEST, FlixRunTestsAction().task)
    }

    fun testTheMenuOffersEveryTaskInOrder() {
        // Built from FlixTask rather than declared, so the menu and the run configuration cannot
        // come to offer different sets of subcommands. Filtered rather than counted, because other
        // modules add to this group -- Show AST comes from backend.
        val tasks = FlixTaskActionGroup().getChildren(null).filterIsInstance<FlixTaskAction>()
        assertEquals(FlixTask.values().map { it.title() }, tasks.map { it.task.title() })
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
