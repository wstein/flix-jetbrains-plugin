package dev.wstein.flixplugin.run

import com.intellij.execution.configurations.LocatableRunConfigurationOptions

/**
 * The persisted state of a Flix run configuration.
 *
 * Only the entry point. The compiler jar is resolved rather than stored -- see
 * [dev.wstein.flixplugin.FlixJar] -- so a saved configuration keeps working after the fork is
 * rebuilt, and a shared `.idea` directory does not carry one developer's absolute paths into
 * everyone else's checkout.
 */
class FlixRunConfigurationOptions : LocatableRunConfigurationOptions() {

    private val entryPointProperty = string("").provideDelegate(this, "entryPoint")

    /**
     * The `--entrypoint` symbol, or blank to let the compiler pick the project default.
     *
     * Held as the raw string the user typed; [dev.wstein.flixplugin.FlixLaunchCommand] normalises it
     * on the way out, so a blank value drops the flag rather than passing it with nothing after it.
     */
    var entryPoint: String?
        get() = entryPointProperty.getValue(this)
        set(value) = entryPointProperty.setValue(this, value)
}
