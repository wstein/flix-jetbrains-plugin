package de.wstein.flixplugin.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.LanguageServerManager
import org.eclipse.lsp4j.ExecuteCommandParams
import java.nio.file.Path

/**
 * Recompiles with phase printing on and opens the ASTs the compiler wrote.
 *
 * ## Why this exists rather than the VS Code command
 *
 * `ShowAstProvider` has been in the compiler all along, reachable only through `lsp/showAst` on
 * Flix's own VS Code protocol — which negotiates no capabilities and which no other client speaks.
 * That is the same shape the diagram was in: implemented, and unreachable from here. The server now
 * serves it as a real `workspace/executeCommand`, and this asks for it.
 *
 * ## Why the server returns a path
 *
 * The compiler writes one file per phase, which is far more than a response should carry. A
 * directory is something any client can open, and the reply is small whatever the program's size.
 *
 * ## Why the server is called directly
 *
 * The reason [FlixShowDiagramAction] gives: LSP4IJ's `CommandExecutor` dereferences a
 * `LanguageServerItem` unconditionally on its failure path, so a request the server declines
 * becomes a `NullPointerException` inside LSP4IJ rather than a message.
 */
class FlixShowAstAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        LanguageServerManager.getInstance(project).getLanguageServer(FlixShowDiagramAction.SERVER_ID)
            .whenComplete { item, lookupError ->
                if (lookupError != null || item == null) {
                    report(project, NO_SERVER)
                    return@whenComplete
                }
                item.server.workspaceService.executeCommand(ExecuteCommandParams(COMMAND, emptyList()))
                    .whenComplete { result, error ->
                        when {
                            error != null -> report(project, error.rootMessage())
                            result is String -> open(project, result)
                            else -> report(project, "The compiler produced no AST directory.")
                        }
                    }
            }
    }

    /**
     * Opens the directory the compiler wrote, or says why it could not.
     *
     * Refreshed before it is looked up: the compiler has just created these files outside the IDE,
     * and a virtual file system that has not been told will report the directory as absent.
     */
    private fun open(project: Project, path: String) {
        ApplicationManager.getApplication().invokeLater {
            val directory = refresh(Path.of(path))
            if (directory == null) {
                report(project, "The compiler reported its ASTs at $path, but nothing is there.")
                return@invokeLater
            }
            // The directory itself cannot be opened in an editor; its files can, and one of them is
            // what a user asking to see the AST wants to read.
            val files = directory.children.orEmpty().filterNot { it.isDirectory }.sortedBy { it.name }
            if (files.isEmpty()) {
                report(project, "The compiler wrote no AST files to $path.")
                return@invokeLater
            }
            files.forEach { FileEditorManager.getInstance(project).openFile(it, false) }
            FileEditorManager.getInstance(project).openFile(files.first(), true)
        }
    }

    private fun refresh(path: Path): VirtualFile? =
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)?.takeIf { it.isDirectory }

    private fun report(project: Project, message: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showWarningDialog(project, message, "Show Flix AST")
        }
    }

    /** The message the server sent, rather than the `CompletionException` wrapped around it. */
    private fun Throwable.rootMessage(): String {
        var cause: Throwable = this
        while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
        return cause.message ?: "The compiler could not produce its ASTs."
    }

    private companion object {
        /** Matches the name the server advertises in `executeCommandProvider`. */
        const val COMMAND = "flix.showAst"

        const val NO_SERVER =
            "The Flix language server is not running, so the ASTs could not be produced. " +
                "Open a Flix file to start it, then try again."
    }
}
