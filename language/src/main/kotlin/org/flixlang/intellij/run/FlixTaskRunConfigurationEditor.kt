package org.flixlang.intellij.run

import com.intellij.openapi.options.SettingsEditor
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import dev.wstein.flixplugin.FlixTask
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import com.intellij.openapi.ui.ComboBox

/**
 * The settings a Flix task configuration shows.
 *
 * Two fields, and deliberately not more. The compiler jar is resolved rather than configured, so
 * that a shared `.idea` directory carries no absolute paths; the working directory is the project,
 * because a Flix subcommand acts on the project it runs in. Adding either as a field would offer a
 * choice whose only correct answer is the one already taken.
 */
class FlixTaskRunConfigurationEditor : SettingsEditor<FlixTaskRunConfiguration>() {

    private val task = ComboBox(FlixTask.values())
    private val arguments = JBTextField()

    init {
        // The list shows what each subcommand does; the command itself is what the user types in a
        // terminal, so both are on the row.
        task.renderer = ListCellRenderer { _, value, _, _, _ ->
            javax.swing.JLabel(value?.let { "${it.title()}  —  flix ${it.command()}" } ?: "")
        }
    }

    override fun resetEditorFrom(configuration: FlixTaskRunConfiguration) {
        task.selectedItem = configuration.task
        arguments.text = configuration.arguments.orEmpty()
    }

    override fun applyEditorTo(configuration: FlixTaskRunConfiguration) {
        configuration.task = task.selectedItem as FlixTask
        configuration.arguments = arguments.text
    }

    override fun createEditor(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Task:", task)
        .addLabeledComponent("Arguments:", arguments)
        .addComponentFillVertically(JPanel(), 0)
        .panel
}
