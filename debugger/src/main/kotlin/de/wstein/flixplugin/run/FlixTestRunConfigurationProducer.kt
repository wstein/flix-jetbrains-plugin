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
import org.flixlang.intellij.run.testSymbolOrNull

/** Creates the same exact-test configuration for both the Run and Debug executors. */
class FlixTestRunConfigurationProducer : LazyRunConfigurationProducer<FlixTestRunConfiguration>() {

    override fun getConfigurationFactory(): ConfigurationFactory =
        ConfigurationTypeUtil.findConfigurationType(FlixTestRunConfigurationType::class.java)
            .configurationFactories
            .single()

    override fun setupConfigurationFromContext(
        configuration: FlixTestRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val declaration = testDeclarationAt(context) ?: return false
        val symbol = declaration.testSymbolOrNull() ?: return false

        configuration.testFilter = symbol
        configuration.name = "test $symbol"
        sourceElement.set(declaration)
        return true
    }

    override fun isConfigurationFromContext(
        configuration: FlixTestRunConfiguration,
        context: ConfigurationContext,
    ): Boolean {
        val symbol = testDeclarationAt(context)?.testSymbolOrNull() ?: return false
        return configuration.testFilter == symbol
    }

    private fun testDeclarationAt(context: ConfigurationContext): FlixDefDecl? {
        val element = context.psiLocation ?: return null
        if (element.containingFile?.fileType != FlixFileType.INSTANCE) return null
        return PsiTreeUtil.getParentOfType(element, FlixDefDecl::class.java, false)
    }
}
