package org.flixlang.intellij.run

import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.openapi.ui.ComponentContainer

/** Re-runs exactly the failed Flix test leaves by translating their stable names into filters. */
internal class FlixRerunFailedTestsAction(
    componentContainer: ComponentContainer,
) : AbstractRerunFailedTestsAction(componentContainer) {

    override fun getRunProfile(environment: ExecutionEnvironment): MyRunProfile? {
        val original = environment.runProfile as? FlixTaskRunConfiguration ?: return null
        val names = getFailedTests(environment.project)
            .asSequence()
            .filter { it.isLeaf }
            .map { it.name }
            .toList()
        val pattern = exactTestPattern(names) ?: return null
        val rerun = original.clone() as? FlixTaskRunConfiguration ?: return null
        rerun.testPattern = pattern
        rerun.name = "${original.name} (failed)"
        return object : MyRunProfile(rerun as RunConfigurationBase<*>) {
            override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? =
                rerun.getState(executor, environment)
        }
    }
}
