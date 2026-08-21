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
 *
 * ## And one thing that is not an argument
 *
 * [allowEffectfulEvaluation] lives here too, because it is the same kind of thing: a decision about
 * this project that has to be made before the moment it applies. See its own documentation for why
 * it is a setting rather than a prompt.
 */
@Service(Service.Level.PROJECT)
@State(name = "FlixSettings", storages = [Storage("flix.xml")])
class FlixSettings : SimplePersistentStateComponent<FlixSettings.FlixState>(FlixState()) {

    class FlixState : BaseState() {
        var extraJvmArgs by string("")
        var extraFlixArgs by string("")
        var allowEffectfulEvaluation by property(false)
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

    /**
     * Whether the debugger may run an expression that performs effects.
     *
     * ## What it permits
     *
     * A watch or a breakpoint condition is compiled and then *run inside the paused program*. An
     * expression the compiler proves pure cannot change what the program does, so it is run without
     * asking. One with an effect can: it writes files, prints, mutates, sends. Running it means the
     * program a reader is inspecting is no longer only the program that was stopped.
     *
     * ## Why a setting rather than a prompt
     *
     * A prompt is the obvious design and the wrong one here. The decision has to be made on the
     * debugger's own thread, with the debuggee suspended and the evaluation lock held; showing a
     * modal dialog from there means waiting on the UI thread while holding what the UI thread may
     * need, which is how a debugger deadlocks. A dialog would also arrive once per breakpoint hit
     * for a condition, which is not a question anybody can answer sixty times.
     *
     * So consent is given deliberately, in advance, and it persists: the refusal names this setting,
     * and turning it on is the gesture. Off by default, because the safe reading of an unset
     * preference is that the program should not be disturbed.
     *
     * Project-level, like everything else here: whether it is acceptable to run effects while
     * debugging is a property of what is being debugged.
     */
    var allowEffectfulEvaluation: Boolean
        get() = state.allowEffectfulEvaluation
        set(value) {
            state.allowEffectfulEvaluation = value
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
