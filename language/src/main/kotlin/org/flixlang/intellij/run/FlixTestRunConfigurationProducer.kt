package org.flixlang.intellij.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import de.wstein.flixplugin.FlixTask
import org.flixlang.intellij.lang.FlixFileType
import org.flixlang.intellij.lang.psi.FlixDefDecl

/** Creates an exact `flix test --filter` configuration from an `@Test` definition. */
class FlixTestRunConfigurationProducer : LazyRunConfigurationProducer<FlixTaskRunConfiguration>() {

    override fun getConfigurationFactory(): ConfigurationFactory =
        ConfigurationTypeUtil.findConfigurationType(FlixTaskRunConfigurationType::class.java)
            .configurationFactories
            .single()

    override fun setupConfigurationFromContext(
        configuration: FlixTaskRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val declaration = testDeclarationAt(context) ?: return false
        val symbol = declaration.testSymbolOrNull() ?: return false

        configuration.task = FlixTask.TEST
        configuration.testFilter = symbol
        configuration.name = "test $symbol"
        sourceElement.set(declaration)
        return true
    }

    override fun isConfigurationFromContext(
        configuration: FlixTaskRunConfiguration,
        context: ConfigurationContext,
    ): Boolean {
        val symbol = testDeclarationAt(context)?.testSymbolOrNull() ?: return false
        return configuration.task == FlixTask.TEST && configuration.testFilter == symbol
    }

    private fun testDeclarationAt(context: ConfigurationContext): FlixDefDecl? {
        val element = context.psiLocation ?: return null
        if (element.containingFile?.fileType != FlixFileType.INSTANCE) return null
        return PsiTreeUtil.getParentOfType(element, FlixDefDecl::class.java, false)
    }
}
