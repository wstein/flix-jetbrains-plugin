package dev.wstein.flixplugin.run

import com.intellij.execution.ui.CommonProgramParametersPanel
import com.intellij.openapi.options.SettingsEditor
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

/**
 * The configuration's settings form.
 *
 * The compiler jar is deliberately absent: it is resolved from the project or `FLIX_JAR`
 * rather than stored, so that a saved configuration survives rebuilding the fork and a shared
 * `.idea` directory carries no absolute paths.
 */
class FlixRunConfigurationEditor : SettingsEditor<FlixRunConfiguration>() {

    private val entryPoint = JBTextField()
    private val vmOptions = RawCommandLineEditor()
    private val programParameters = CommonProgramParametersPanel()

    override fun createEditor(): JComponent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent("Entry point:", entryPoint)
            .addLabeledComponent("VM options:", vmOptions)
            .addComponent(programParameters)
            .panel

    override fun resetEditorFrom(configuration: FlixRunConfiguration) {
        entryPoint.text = configuration.entryPoint.orEmpty()
        vmOptions.text = configuration.vmOptions.orEmpty()
        programParameters.reset(configuration)
    }

    override fun applyEditorTo(configuration: FlixRunConfiguration) {
        configuration.entryPoint = entryPoint.text
        configuration.vmOptions = vmOptions.text
        programParameters.applyTo(configuration)
    }
}
