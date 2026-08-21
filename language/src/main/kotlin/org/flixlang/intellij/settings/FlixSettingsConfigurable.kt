package org.flixlang.intellij.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * **Settings > Languages & Frameworks > Flix**.
 *
 * Two argument fields, matching the two the VS Code extension has that mean anything here, and one
 * checkbox that is not an argument at all. The compiler jar is not among them on purpose: it is
 * resolved from the project root or `$FLIX_JAR` ([de.wstein.flixplugin.FlixJar]), so that a shared
 * `.idea` directory carries no absolute paths and every process the plugin starts agrees on one
 * compiler.
 *
 * The checkbox is where consent to run effectful expressions in the debugger is given. It is here
 * rather than in a dialog because the decision has to be made before the moment it applies -- see
 * [FlixSettings.allowEffectfulEvaluation] -- and a place a reader can find it afterwards, and change
 * their mind, is worth more than one they have to answer under time pressure.
 */
class FlixSettingsConfigurable(private val project: Project) : Configurable {

    private val jvmArgs = JBTextField()
    private val flixArgs = JBTextField()
    private val allowEffects = JBCheckBox("Allow the debugger to run expressions that perform effects")

    override fun getDisplayName(): String = "Flix"

    override fun createComponent(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("JVM arguments:", jvmArgs)
        .addComponentToRightColumn(hint("Passed to every Flix process, before -jar."))
        .addLabeledComponent("Flix arguments:", flixArgs)
        .addComponentToRightColumn(hint("Passed to the language server and to every task, after the subcommand."))
        .addComponentToRightColumn(
            hint("The run/debug configuration takes its arguments from its own fields instead."),
        )
        .addSeparator()
        .addComponent(allowEffects)
        .addComponentToRightColumn(
            hint(
                "A watch or breakpoint condition is run inside the paused program. A pure " +
                    "expression cannot change what it does; an effectful one can write, print or " +
                    "mutate. Off by default, and asked for here rather than in a dialog: the " +
                    "decision is made on the debugger's thread, and a condition would ask on every hit.",
            ),
        )
        .addComponentFillVertically(JPanel(), 0)
        .panel

    override fun isModified(): Boolean {
        val settings = FlixSettings.getInstance(project)
        return jvmArgs.text != settings.extraJvmArgs ||
            flixArgs.text != settings.extraFlixArgs ||
            allowEffects.isSelected != settings.allowEffectfulEvaluation
    }

    override fun apply() {
        val settings = FlixSettings.getInstance(project)
        settings.extraJvmArgs = jvmArgs.text
        settings.extraFlixArgs = flixArgs.text
        settings.allowEffectfulEvaluation = allowEffects.isSelected
    }

    override fun reset() {
        val settings = FlixSettings.getInstance(project)
        jvmArgs.text = settings.extraJvmArgs
        flixArgs.text = settings.extraFlixArgs
        allowEffects.isSelected = settings.allowEffectfulEvaluation
    }

    private fun hint(text: String): JBLabel =
        JBLabel(text).apply {
            font = UIUtil.getFont(UIUtil.FontSize.SMALL, font)
            foreground = UIUtil.getContextHelpForeground()
        }
}
