package org.flixlang.intellij.run

import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.openapi.util.Key
import jetbrains.buildServer.messages.serviceMessages.ServiceMessageVisitor
import java.nio.file.Path

/**
 * Reads `flix test --events-json` off the process's output and drives the test tree with it.
 *
 * ## Why this override point
 *
 * `processServiceMessages` is called once per whole line, and its boolean return is exactly the
 * question being answered: `true` means "handled", `false` makes the platform call
 * `fireOnUncapturedOutput` and print the line to the console verbatim. So a line that is not one of
 * ours needs no handling at all -- returning `false` already does the right thing with it, which is
 * why a JVM warning shows up in the console as itself rather than as a mangled test.
 *
 * ## Why the events are turned back into text
 *
 * Each event becomes the service messages the platform already knows how to parse, and those go
 * through `super.processServiceMessages`. Building `TestStartedEvent` and friends by hand would
 * work too, and would put this class in the business of tracking what those constructors expect
 * across platform versions for no gain.
 *
 * One JSON line can produce more than one message -- a failure is a `testFailed` and then a
 * `testFinished`, a skip is three -- which is why the mapping returns a list.
 */
class FlixTestEventConverter(
    testFrameworkName: String,
    consoleProperties: TestConsoleProperties,
    private val workingDirectory: Path,
) : OutputToGeneralTestEventsConverter(testFrameworkName, consoleProperties) {

    override fun processServiceMessages(
        text: String,
        outputType: Key<*>,
        visitor: ServiceMessageVisitor,
    ): Boolean {
        val event = FlixTestEventParser.parse(text) ?: return false

        // Output belongs to no test, so it cannot be a service message: `testStdOut` needs a test
        // name, and the only name available would be whichever test happens to be running, which
        // the compiler's own runner deliberately refuses to guess at. It goes to the console as
        // the program's own writing instead, which is what it is.
        //
        // Through the base class's own entry point rather than reaching past it to `processor`.
        // The two behave identically when a processor is attached, and identically when one is not
        // -- `fireOnUncapturedOutput` drops the line just the same, and so does the platform's
        // fallback for a `false` return, so there is no arrangement here that rescues output
        // arriving before the console attaches. What this buys is smaller and worth stating
        // plainly: the empty-text guard, and one less place that depends on how the base class
        // stores its processor.
        if (event is FlixTestEvent.Output) {
            fireOnUncapturedOutput(event.line + "\n", ProcessOutputTypes.STDOUT)
            return true
        }

        for (message in FlixTestServiceMessages.messagesFor(event, workingDirectory)) {
            super.processServiceMessages(message, outputType, visitor)
        }
        return true
    }
}
