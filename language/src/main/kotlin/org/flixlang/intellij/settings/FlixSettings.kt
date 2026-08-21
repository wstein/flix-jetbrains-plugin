package org.flixlang.intellij.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil

/**
 * Extra arguments for the Flix processes this plugin starts.
 *
 * The counterpart of the VS Code extension's `flix.extraJvmArgs` and `flix.extraFlixArgs`, and the
 * only two settings there that have anything to configure here: `flix.clearOutput.enabled` governs
 * a single shared output channel, and an IntelliJ console belongs to one run and is fresh already.
 *
 * ## What they reach, and what they deliberately do not
 *
 * The language server and every task -- `build`, `check`, `test` and the rest. **Not** the Flix
 * run/debug configuration, which has its own VM options and program arguments fields. That is not
 * an oversight: a debug launch already passes `--Xdebug`, and
 * [de.wstein.flixplugin.FlixLaunchCommand] documents that a second occurrence is rejected outright
 * as `Unknown option --Xdebug`. A global "extra Flix arguments" applied there would turn one
 * plausible setting into a launch that cannot start, and the error names neither the setting nor
 * the duplicate.
 *
 * ## Why they are split in two
 *
 * The same reason `FlixLaunchCommand.task` takes two lists. A JVM option after `-jar` is the
 * compiler's input, and a compiler option before the subcommand is treated as global and stops
 * command parsing. One combined field would have to guess which half a given argument belongs to.
 *
 * Project-level rather than application-level: the arguments a project needs -- heap for a large
 * one, a `--github-token` for its dependencies -- are properties of that project.
 */
@Service(Service.Level.PROJECT)
@State(name = "FlixSettings", storages = [Storage("flix.xml")])
class FlixSettings : SimplePersistentStateComponent<FlixSettings.FlixState>(FlixState()) {

    class FlixState : BaseState() {
        var extraJvmArgs by string("")
        var extraFlixArgs by string("")
    }

    /** Additional JVM arguments, separated by spaces, as typed. */
    var extraJvmArgs: String
        get() = state.extraJvmArgs.orEmpty()
        set(value) {
            state.extraJvmArgs = value
        }

    /** Additional Flix compiler options, separated by spaces, as typed. */
    var extraFlixArgs: String
        get() = state.extraFlixArgs.orEmpty()
        set(value) {
            state.extraFlixArgs = value
        }

    /** [extraJvmArgs] split the way a command line splits it, honouring quotes. */
    val jvmArguments: List<String>
        get() = ParametersListUtil.parse(extraJvmArgs)

    /** [extraFlixArgs] split the way a command line splits it, honouring quotes. */
    val flixArguments: List<String>
        get() = ParametersListUtil.parse(extraFlixArgs)

    companion object {
        fun getInstance(project: Project): FlixSettings = project.service()
    }
}
