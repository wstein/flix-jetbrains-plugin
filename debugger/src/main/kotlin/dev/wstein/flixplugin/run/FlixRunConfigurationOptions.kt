package dev.wstein.flixplugin.run

import com.intellij.execution.configurations.LocatableRunConfigurationOptions

/**
 * The persisted state of a Flix run configuration.
 *
 * The launch settings and entry point. The compiler jar is resolved rather than stored -- see
 * [dev.wstein.flixplugin.FlixJar] -- so a saved configuration keeps working after the fork is
 * rebuilt, and a shared `.idea` directory does not carry one developer's absolute paths into
 * everyone else's checkout.
 */
class FlixRunConfigurationOptions : LocatableRunConfigurationOptions() {

    private val entryPointProperty = string("").provideDelegate(this, "entryPoint")
    private val vmOptionsProperty = string("").provideDelegate(this, "vmOptions")
    private val programParametersProperty = string("").provideDelegate(this, "programParameters")
    private val workingDirectoryProperty = string("").provideDelegate(this, "workingDirectory")
    private val envsProperty = map<String, String>().provideDelegate(this, "envs")
    private val passParentEnvsProperty = property(true).provideDelegate(this, "passParentEnvs")

    /**
     * The `--entrypoint` symbol, or blank to let the compiler pick the project default.
     *
     * Held as the raw string the user typed; [dev.wstein.flixplugin.FlixLaunchCommand] normalises it
     * on the way out, so a blank value drops the flag rather than passing it with nothing after it.
     */
    var entryPoint: String?
        get() = entryPointProperty.getValue(this)
        set(value) = entryPointProperty.setValue(this, value)

    var vmOptions: String?
        get() = vmOptionsProperty.getValue(this)
        set(value) = vmOptionsProperty.setValue(this, value)

    var programParameters: String?
        get() = programParametersProperty.getValue(this)
        set(value) = programParametersProperty.setValue(this, value)

    var workingDirectory: String?
        get() = workingDirectoryProperty.getValue(this)
        set(value) = workingDirectoryProperty.setValue(this, value)

    var envs: MutableMap<String, String>
        get() = envsProperty.getValue(this)
        set(value) = envsProperty.setValue(this, value)

    var passParentEnvs: Boolean
        get() = passParentEnvsProperty.getValue(this)
        set(value) = passParentEnvsProperty.setValue(this, value)
}
