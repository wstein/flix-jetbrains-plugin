package org.flixlang.intellij.run

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import dev.wstein.flixplugin.FlixTask

/**
 * The Flix subcommands, as menu items.
 *
 * Built from [FlixTask] rather than declared one by one in the descriptor, so the menu and the run
 * configuration cannot come to offer different sets. Adding a task adds a menu item.
 *
 * Each item runs a **temporary run configuration** rather than a bare process. That is what makes
 * these not terminal wrappers: the task lands in the Run widget, can be stopped and re-run, keeps
 * its console and its exit code, and can be saved and edited like any other configuration if the
 * user wants arguments on it.
 */
class FlixTaskActionGroup : ActionGroup() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> =
        FlixTask.values().map(::FlixTaskAction).toTypedArray()
}

/** Runs one [FlixTask] in the current project. */
class FlixTaskAction(private val task: FlixTask) : AnAction(task.title(), task.description(), null) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ProgramRunnerUtil.executeConfiguration(
            temporaryConfiguration(project, task),
            DefaultRunExecutor.getRunExecutorInstance(),
        )
    }

    companion object {
        /**
         * A run configuration for [task], registered as temporary.
         *
         * Temporary rather than saved: the Run widget shows it and it can be re-run, while the
         * user's saved configurations are not filled with one entry per menu click. Promoting it to
         * a permanent one is the platform's own "Save Configuration" action, so nothing here has to
         * offer that.
         */
        internal fun temporaryConfiguration(project: Project, task: FlixTask): RunnerAndConfigurationSettings {
            val runManager = RunManager.getInstance(project)
            val factory = FlixTaskRunConfigurationType().configurationFactories.first()
            val settings = runManager.createConfiguration("flix ${task.command()}", factory)
            (settings.configuration as FlixTaskRunConfiguration).task = task
            runManager.setTemporaryConfiguration(settings)
            return settings
        }
    }
}
