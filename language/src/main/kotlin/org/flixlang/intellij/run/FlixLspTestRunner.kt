package org.flixlang.intellij.run

import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * Optional bridge to the language server's streaming test protocol.
 *
 * It is implemented by the backend module, where LSP4IJ is available. The language module keeps
 * the CLI command as an explicit fallback so tests still run in IDEs where that optional module is
 * absent, against an older server, or after the server has stopped.
 */
interface FlixLspTestRunner {
    fun createProcess(
        filters: List<String>,
        fallbackCommand: List<String>,
        workingDirectory: Path,
    ): ProcessHandler

    companion object {
        fun getInstance(project: Project): FlixLspTestRunner? = project.getService(FlixLspTestRunner::class.java)
    }
}
