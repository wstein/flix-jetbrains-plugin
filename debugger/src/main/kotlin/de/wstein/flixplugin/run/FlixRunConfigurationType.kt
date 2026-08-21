package de.wstein.flixplugin.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import org.flixlang.intellij.icons.FlixIcons

/**
 * The single Flix run/debug configuration type.
 *
 * One type covers both Run and Debug rather than two, because they differ only in the compiler
 * invocation and whether a JDWP agent is attached -- see [FlixRunConfiguration]. Two types would
 * present the user with a choice that has no meaning and let the two drift apart.
 *
 * Registered from the debugger content module, which is the only one that depends on the Java
 * plugin. The consequence is deliberate and worth stating: in an IDE without Java support there is
 * no Flix run configuration at all. Debugging needs the Java debugger regardless, and splitting Run
 * out to keep it available would produce exactly the two-types-that-drift problem above. The
 * language layer -- syntax, parsing, LSP -- keeps loading either way, which is the property ADR
 * 0001 actually requires.
 */
class FlixRunConfigurationType : ConfigurationTypeBase(
    ID,
    "Flix",
    "Run a Flix program with the resolved Flix compiler",
    NotNullLazyValue.createValue { FlixIcons.FILE },
) {

    init {
        addFactory(FlixConfigurationFactory(this))
    }

    class FlixConfigurationFactory(type: FlixRunConfigurationType) : ConfigurationFactory(type) {

        override fun getId(): String = ID

        override fun createTemplateConfiguration(project: Project): RunConfiguration =
            FlixRunConfiguration(project, this, "Flix")

        override fun getOptionsClass(): Class<out RunConfigurationOptions> =
            FlixRunConfigurationOptions::class.java
    }

    companion object {
        /**
         * Persisted in `.idea/workspace.xml`, so it is API: changing it orphans every configuration
         * a user has already saved.
         */
        const val ID: String = "FlixRunConfiguration"
    }
}
