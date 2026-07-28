package dev.wstein.flixplugin.run

import com.intellij.openapi.options.SettingsEditor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

/**
 * The configuration's settings form.
 *
 * One field, because there is one decision to make. The compiler jar is deliberately absent: it is
 * resolved from the project or `FLIX_FORK_JAR` rather than stored, so that a saved configuration
 * survives rebuilding the fork and a shared `.idea` directory carries no absolute paths.
 */
class FlixRunConfigurationEditor : SettingsEditor<FlixRunConfiguration>() {

    private val entryPoint = JBTextField()

    override fun createEditor(): JComponent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent("Entry point:", entryPoint)
            .addComponentToRightColumn(
                JBLabel("Qualified symbol, for example Main.main. Leave blank for the project default.")
                    .apply { componentStyle = UIUtil.ComponentStyle.SMALL },
            )
            .panel

    override fun resetEditorFrom(configuration: FlixRunConfiguration) {
        entryPoint.text = configuration.entryPoint.orEmpty()
    }

    override fun applyEditorTo(configuration: FlixRunConfiguration) {
        configuration.entryPoint = entryPoint.text
    }
}
