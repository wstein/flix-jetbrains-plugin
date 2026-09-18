package de.wstein.flixplugin.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ModuleRunProfile
import com.intellij.execution.configurations.RemoteConnection
import com.intellij.execution.configurations.RemoteConnectionCreator
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import de.wstein.flixplugin.FlixTask
import org.flixlang.intellij.run.FlixTaskRunConfiguration
import java.io.IOException

/** Runs or debugs exactly one `@Test` definition in the compiler JVM that executes it. */
class FlixTestRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String,
) : FlixTaskRunConfiguration(project, factory, name), ModuleRunProfile {

    init {
        task = FlixTask.TEST
    }

    override fun getModules(): Array<Module> = ModuleManager.getInstance(project).modules

    override fun checkConfiguration() {
        super.checkConfiguration()
        if (task != FlixTask.TEST || (testFilter.isNullOrBlank() && testPattern.isNullOrBlank())) {
            throw RuntimeConfigurationError("Select a Flix test or source scope to run or debug.")
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        if (executor.id != DefaultDebugExecutor.EXECUTOR_ID) return super.getState(executor, environment)

        val launch = try {
            FlixTestLaunch.create(
                commandFor(resolveJar()),
                System.getenv(FlixLaunch.JAVA_TOOL_OPTIONS),
            )
        } catch (e: IllegalStateException) {
            throw ExecutionException(e.message, e)
        } catch (e: IOException) {
            throw ExecutionException("Could not allocate a port for the test debugger", e)
        }
        return FlixTestDebugState(environment, launch, workingDirectory(), this)
    }

    private class FlixTestDebugState(
        environment: ExecutionEnvironment,
        launch: FlixTestLaunch,
        workingDirectory: java.nio.file.Path,
        configuration: FlixTestRunConfiguration,
    ) : FlixTaskRunConfiguration.FlixTaskState(
        environment,
        launch.command,
        workingDirectory,
        configuration,
    ), RemoteConnectionCreator {

        private val connection: RemoteConnection = launch.remoteConnection

        override fun createRemoteConnection(environment: ExecutionEnvironment): RemoteConnection = connection

        override fun isPollConnection(): Boolean = true
    }
}
