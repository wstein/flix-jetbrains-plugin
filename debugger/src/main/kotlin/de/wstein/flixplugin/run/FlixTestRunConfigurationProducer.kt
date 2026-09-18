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
import org.flixlang.intellij.lang.FlixFile
import org.flixlang.intellij.run.testSymbolOrNull
import org.flixlang.intellij.run.testPatternOrNull

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
        val declaration = testDeclarationAt(context)
        val symbol = declaration?.testSymbolOrNull()
        if (symbol != null) {
            configuration.testFilter = symbol
            configuration.name = "test $symbol"
            sourceElement.set(declaration)
            return true
        }

        val file = flixFileAt(context) ?: return false
        val pattern = file.testPatternOrNull() ?: return false
        configuration.testPattern = pattern
        configuration.name = "test ${file.name}"
        sourceElement.set(file)
        return true
    }

    override fun isConfigurationFromContext(
        configuration: FlixTestRunConfiguration,
        context: ConfigurationContext,
    ): Boolean {
        val symbol = testDeclarationAt(context)?.testSymbolOrNull()
        if (symbol != null) return configuration.testFilter == symbol
        val pattern = flixFileAt(context)?.testPatternOrNull() ?: return false
        return configuration.testPattern == pattern
    }

    private fun testDeclarationAt(context: ConfigurationContext): FlixDefDecl? {
        val element = context.psiLocation ?: return null
        if (element.containingFile?.fileType != FlixFileType.INSTANCE) return null
        return PsiTreeUtil.getParentOfType(element, FlixDefDecl::class.java, false)
    }


    private fun flixFileAt(context: ConfigurationContext): FlixFile? {
        val file = context.psiLocation?.containingFile as? FlixFile ?: return null
        return file.takeIf { it.fileType == FlixFileType.INSTANCE }
    }
}
