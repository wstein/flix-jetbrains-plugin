package org.flixlang.intellij.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.FileUrlProvider
import com.intellij.execution.testframework.sm.SMCustomMessagesParsing
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import java.nio.file.Path

/**
 * The test tree's configuration: which locator makes a row clickable, and which converter fills it.
 *
 * ## Why [SMCustomMessagesParsing]
 *
 * It is the only hook the platform offers for a runner that does not already speak service
 * messages: `SMTestRunnerConnectionUtil.attachEventsProcessors` checks the console properties for
 * this interface and otherwise builds a plain `OutputToGeneralTestEventsConverter`, which would
 * find nothing in the compiler's JSON and print all of it as console text.
 *
 * ## Why [FileUrlProvider] rather than a locator of this plugin's own
 *
 * Because a Flix test's location is a file, a line and a column, which is exactly what the
 * platform's own provider resolves -- and it resolves it through `TestsLocationProviderUtil`, so a
 * path that is relative, or that matches more than one file in the project, is handled by the code
 * that already handles that for every other language. A locator here would have to reimplement
 * that to be no more correct.
 *
 * The class lives in the always-loaded `language` module because
 * `lib/intellij.platform.smRunner.jar` is a top-level platform jar rather than part of the Java
 * plugin, so nothing here narrows where the module can load. The test *task* has the same property
 * -- `flix test` needs neither LSP4IJ nor the Java plugin -- which is why the run configuration it
 * belongs to lives here too.
 */
class FlixTestConsoleProperties(
    configuration: RunConfiguration,
    executor: Executor,
    private val workingDirectory: Path,
) : SMTRunnerConsoleProperties(configuration, FRAMEWORK_NAME, executor), SMCustomMessagesParsing {

    override fun createTestEventsConverter(
        testFrameworkName: String,
        consoleProperties: TestConsoleProperties,
    ): OutputToGeneralTestEventsConverter =
        FlixTestEventConverter(testFrameworkName, consoleProperties, workingDirectory)

    override fun getTestLocator(): SMTestLocator = FileUrlProvider.INSTANCE

    companion object {
        /** What the tree calls this runner, and the prefix on any problem it logs. */
        const val FRAMEWORK_NAME: String = "Flix"
    }
}
