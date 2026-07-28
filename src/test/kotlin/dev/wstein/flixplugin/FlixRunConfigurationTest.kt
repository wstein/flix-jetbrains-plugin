package dev.wstein.flixplugin

import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.wstein.flixplugin.run.FlixRunConfiguration
import dev.wstein.flixplugin.run.FlixRunConfigurationType

/**
 * How the run configuration behaves when it cannot run.
 *
 * The compiler jar is resolved rather than stored, which keeps a saved configuration working after
 * the fork is rebuilt — but it means the failure surfaces at a different time, and where it
 * surfaces decides whether the user can act on it. Reported from the dialog, it names what to do.
 * Left to launch, it is a process that exits immediately with the reason buried in a console.
 *
 * Also covers the assembled configuration type being reachable at all, since a producer that cannot
 * find its factory is how the gutter arrow silently did nothing once before.
 */
class FlixRunConfigurationTest : BasePlatformTestCase() {

    private fun newConfiguration(): FlixRunConfiguration {
        val type = ConfigurationTypeUtil.findConfigurationType(FlixRunConfigurationType::class.java)
        assertNotNull("the Flix configuration type is not registered in the assembled plugin", type)
        val factory = type.configurationFactories.single()
        return factory.createTemplateConfiguration(project) as FlixRunConfiguration
    }

    fun testRefusesInTheDialogWhenNoCompilerJarCanBeFound() {
        // The fixture project has no flix-vendor-*.jar, which is exactly the state a new user is in.
        try {
            newConfiguration().checkConfiguration()
            fail("expected the configuration to refuse while it can still be corrected")
        } catch (expected: RuntimeConfigurationError) {
            val message = expected.message.orEmpty()
            // "not found" is not actionable. Naming the file and the override is.
            assertTrue(
                "the message must say what to do, got: $message",
                message.contains("flix-vendor-*.jar") && message.contains(FlixJar.PINNED_JAR_ENV),
            )
        }
    }

    // The accepting side is deliberately not tested here. It needs a real jar on the project's
    // basePath, which a light fixture cannot supply, and the env override that would fake it is
    // unset on every machine including CI -- so the test would silently no-op rather than pass.
    // `FlixJarTest` covers resolution itself against the pure function, where a fixture jar is cheap.

    fun testTheEntryPointRoundTripsThroughTheConfiguration() {
        // The producer writes it and the launch reads it back; a configuration that dropped it would
        // run the project default while the gutter said otherwise, which is only visibly wrong once
        // a project has more than one entry point.
        val configuration = newConfiguration()
        configuration.entryPoint = "Foo.Bar.demo"
        assertEquals("Foo.Bar.demo", configuration.entryPoint)
    }
}
