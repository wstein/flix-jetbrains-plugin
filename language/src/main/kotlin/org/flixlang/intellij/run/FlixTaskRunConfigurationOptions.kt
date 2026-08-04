package org.flixlang.intellij.run

import com.intellij.execution.configurations.LocatableRunConfigurationOptions
import dev.wstein.flixplugin.FlixTask

/**
 * The persisted state of a Flix task configuration.
 *
 * The task is stored as its **command** rather than its enum name, because that string is the one
 * the compiler defines: an enum constant could be renamed without changing what runs, and a saved
 * configuration would then quietly follow the rename. The compiler jar is resolved rather than
 * stored, for the reason [dev.wstein.flixplugin.FlixJar] gives.
 */
class FlixTaskRunConfigurationOptions : LocatableRunConfigurationOptions() {

    private val taskProperty = string(FlixTask.BUILD.command()).provideDelegate(this, "task")
    private val argumentsProperty = string("").provideDelegate(this, "arguments")

    /** The subcommand, as [FlixTask.command] spells it. */
    var task: String?
        get() = taskProperty.getValue(this)
        set(value) = taskProperty.setValue(this, value)

    /** Extra arguments for the subcommand, which go after it on the command line. */
    var arguments: String?
        get() = argumentsProperty.getValue(this)
        set(value) = argumentsProperty.setValue(this, value)
}
