package org.flixlang.intellij.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * **Settings > Languages & Frameworks > Flix**.
 *
 * Two fields, matching the two the VS Code extension has that mean anything here. The compiler jar
 * is not among them on purpose: it is resolved from the project root or `$FLIX_JAR`
 * ([de.wstein.flixplugin.FlixJar]), so that a shared `.idea` directory carries no absolute paths
 * and every process the plugin starts agrees on one compiler.
 */
class FlixSettingsConfigurable(private val project: Project) : Configurable {

    private val jvmArgs = JBTextField()
    private val flixArgs = JBTextField()

    override fun getDisplayName(): String = "Flix"

    override fun createComponent(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("JVM arguments:", jvmArgs)
        .addComponentToRightColumn(hint("Passed to every Flix process, before -jar."))
        .addLabeledComponent("Flix arguments:", flixArgs)
        .addComponentToRightColumn(hint("Passed to the language server and to every task, after the subcommand."))
        .addComponentToRightColumn(
            hint("The run/debug configuration takes its arguments from its own fields instead."),
        )
        .addComponentFillVertically(JPanel(), 0)
        .panel

    override fun isModified(): Boolean {
        val settings = FlixSettings.getInstance(project)
        return jvmArgs.text != settings.extraJvmArgs || flixArgs.text != settings.extraFlixArgs
    }

    override fun apply() {
        val settings = FlixSettings.getInstance(project)
        settings.extraJvmArgs = jvmArgs.text
        settings.extraFlixArgs = flixArgs.text
    }

    override fun reset() {
        val settings = FlixSettings.getInstance(project)
        jvmArgs.text = settings.extraJvmArgs
        flixArgs.text = settings.extraFlixArgs
    }

    private fun hint(text: String): JBLabel =
        JBLabel(text).apply {
            font = UIUtil.getFont(UIUtil.FontSize.SMALL, font)
            foreground = UIUtil.getContextHelpForeground()
        }
}
