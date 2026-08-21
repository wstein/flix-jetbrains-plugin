package de.wstein.flixplugin.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.flixlang.intellij.lang.FlixFileType
import org.flixlang.intellij.lang.psi.FlixDefDecl
import org.flixlang.intellij.run.entryPointSymbolOrNull

/**
 * Creates a Flix run configuration from a `def` in the editor -- the gutter arrow, and
 * Run/Debug from the context menu.
 *
 * Claims only `.flix` files, so no other language's context action is affected.
 *
 * ## Why it matches on the entry point rather than on the element
 *
 * [isConfigurationFromContext] decides whether an existing configuration already covers this
 * context. Comparing the PSI element would make every edit above the declaration look like a new
 * context and pile up near-identical configurations; comparing the entry-point symbol -- the only
 * thing that actually differs between two Flix configurations -- reuses the existing one. That is
 * also what makes the gutter's Run and Debug actions land on the *same* configuration rather than
 * silently creating a second.
 */
class FlixRunConfigurationProducer : LazyRunConfigurationProducer<FlixRunConfiguration>() {

    /**
     * The factory of the **registered** configuration type, not a fresh instance of it.
     *
     * Constructing `FlixRunConfigurationType()` here would compile and look right while producing a
     * second, unregistered type object. The platform identifies configurations by their factory and
     * type, so a configuration built from that duplicate is not the same kind of thing as one the
     * user saves, restores from `workspace.xml`, or edits in the dialog -- and the mismatch shows up
     * as a configuration that will not run or will not be recognised again, far from its cause.
     */
    override fun getConfigurationFactory(): ConfigurationFactory =
        ConfigurationTypeUtil.findConfigurationType(FlixRunConfigurationType::class.java)
            .configurationFactories
            .single()

    override fun setupConfigurationFromContext(
        configuration: FlixRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val declaration = flixDeclarationAt(context) ?: return false
        val symbol = declaration.entryPointSymbolOrNull() ?: return false

        configuration.entryPoint = symbol
        configuration.name = symbol
        // Anchors the configuration to the declaration rather than to the caret, so re-running from
        // anywhere inside the same `def` is recognised as the same context.
        sourceElement.set(declaration)
        return true
    }

    override fun isConfigurationFromContext(
        configuration: FlixRunConfiguration,
        context: ConfigurationContext,
    ): Boolean {
        val symbol = flixDeclarationAt(context)?.entryPointSymbolOrNull() ?: return false
        return symbol == configuration.entryPoint
    }

    /**
     * The `def` the context sits in, or `null` if the context is not inside one in a Flix file.
     *
     * The file-type check is what keeps this producer out of other languages' context menus: a
     * `ConfigurationContext` is offered to every producer regardless of what the user clicked.
     */
    private fun flixDeclarationAt(context: ConfigurationContext): FlixDefDecl? {
        val element = context.psiLocation ?: return null
        if (element.containingFile?.fileType != FlixFileType.INSTANCE) return null
        return PsiTreeUtil.getParentOfType(element, FlixDefDecl::class.java, false)
    }
}
