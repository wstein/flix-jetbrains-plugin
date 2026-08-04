package org.flixlang.intellij.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import org.flixlang.intellij.icons.FlixIcons

/**
 * The Flix task configuration type: one type, with the subcommand as a field.
 *
 * A type per subcommand would put eleven near-identical entries in the New Configuration list and
 * give the user a choice the compiler models as one argument. The task is therefore a field, the
 * way Gradle and Cargo model theirs.
 *
 * Registered from the **language** content module, which is the only always-loaded module that can
 * register platform extensions -- `shared` carries no platform dependency at all. That placement is
 * the point rather than an accident: building, checking and testing a Flix project needs neither
 * LSP4IJ nor the Java plugin, so it must not sit in a module that requires one. The Flix *debug*
 * configuration does need the Java plugin and stays where it is.
 */
class FlixTaskRunConfigurationType : ConfigurationTypeBase(
    ID,
    "Flix Task",
    "Run a Flix subcommand against the resolved Flix compiler",
    NotNullLazyValue.createValue { FlixIcons.FILE },
) {

    init {
        addFactory(FlixTaskConfigurationFactory(this))
    }

    class FlixTaskConfigurationFactory(type: FlixTaskRunConfigurationType) : ConfigurationFactory(type) {

        override fun getId(): String = ID

        override fun createTemplateConfiguration(project: Project): RunConfiguration =
            FlixTaskRunConfiguration(project, this, "Flix Task")

        override fun getOptionsClass(): Class<out RunConfigurationOptions> =
            FlixTaskRunConfigurationOptions::class.java
    }

    companion object {
        /**
         * Persisted in `.idea/workspace.xml`, so it is API: changing it orphans every configuration
         * a user has already saved. Deliberately distinct from the debug configuration's
         * `FlixRunConfiguration`.
         */
        const val ID: String = "FlixTaskRunConfiguration"
    }
}
