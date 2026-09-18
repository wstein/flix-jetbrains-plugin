package de.wstein.flixplugin.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import org.flixlang.intellij.icons.FlixIcons

/** The one configuration used by both Run Test and Debug Test. */
class FlixTestRunConfigurationType : ConfigurationTypeBase(
    ID,
    "Flix Test",
    "Run or debug an individual Flix test",
    NotNullLazyValue.createValue { FlixIcons.FILE },
) {

    init {
        addFactory(FlixTestConfigurationFactory(this))
    }

    class FlixTestConfigurationFactory(type: FlixTestRunConfigurationType) : ConfigurationFactory(type) {

        override fun getId(): String = ID

        override fun createTemplateConfiguration(project: Project): RunConfiguration =
            FlixTestRunConfiguration(project, this, "Flix Test")

        override fun getOptionsClass(): Class<out RunConfigurationOptions> =
            org.flixlang.intellij.run.FlixTaskRunConfigurationOptions::class.java
    }

    companion object {
        const val ID: String = "FlixTestRunConfiguration"
    }
}
