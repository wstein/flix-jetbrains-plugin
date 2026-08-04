package dev.wstein.flixplugin.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.redhat.devtools.lsp4ij.commands.CommandExecutor
import com.redhat.devtools.lsp4ij.commands.LSPCommandContext
import org.eclipse.lsp4j.Command

/**
 * Shows the structural diagram of the trait or module at the caret.
 *
 * ## Why this exists rather than the hover's own link
 *
 * A Flix hover offers `[View Diagram](command:flix.showDiagram?Name)`. `command:` URIs are a VS Code
 * markdown extension, and nothing in IntelliJ follows one: LSP4IJ's
 * `LSPDocumentationLinkHandler.resolveLink` handles `file://` and returns `null` for everything
 * else, so the platform hands the unknown scheme to the operating system -- which is why clicking
 * it used to raise *"command:flix.showDiagram?maxDemo can't be found"* from Finder.
 *
 * That is not something the client can be wired around: the handler is LSP4IJ's, and a link it
 * declines never reaches any code of ours. The server therefore withholds the link from clients
 * that cannot follow it, and the same diagram is reached here instead.
 *
 * ## How the name is chosen
 *
 * The identifier under the caret, read from the document rather than resolved through the PSI. The
 * server looks a diagram up by name and answers "no such trait or module" for anything it does not
 * know, so resolution buys nothing that the server does not already do -- and asking the PSI to
 * resolve a name means teaching it to, which the Flix parser deliberately does not.
 */
class FlixShowDiagramAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    /** Offered only where it could work: a `.flix` editor with an identifier under the caret. */
    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        // Compared by extension rather than through FlixFileType, which lives in the language
        // module: backend depends on it at run time but not at compile time, and widening that for
        // one check would couple the two modules for no gain. `.flix` is the only extension this
        // plugin claims, and checkIntegrationGlue keeps it that way.
        val isFlix = e.getData(CommonDataKeys.PSI_FILE)?.virtualFile?.extension == "flix"
        e.presentation.isEnabledAndVisible =
            e.project != null && isFlix && editor != null && identifierAt(editor) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val itemName = identifierAt(editor) ?: return

        val view = FlixDiagramView.getInstance(project)
        view.activate()

        val command = Command("Show Flix Diagram", COMMAND, listOf<Any>(itemName))
        val response = CommandExecutor.executeCommand(
            LSPCommandContext(command, psiFile, LSPCommandContext.ExecutedBy.OTHER, editor, null)
                // The server reports "no such item" as a failed request, which is information for
                // this window rather than something to interrupt the user with.
                .setShowNotificationError(false),
        )

        if (!response.exists()) {
            // No running server claims the command -- an older compiler, or the server not started.
            view.showMessage(
                "The Flix language server did not offer '$COMMAND'. " +
                    "Diagrams need a compiler build that serves them over LSP.",
            )
            return
        }

        val pending = response.response()
        if (pending == null) {
            view.showMessage("The Flix language server accepted '$COMMAND' but returned nothing.")
            return
        }

        pending.whenComplete { result, error ->
            ApplicationManager.getApplication().invokeLater {
                when {
                    error != null -> view.showMessage(rootCauseMessage(error))
                    result is String && result.contains("<svg") -> view.showDiagram(result)
                    // An item that exists but has nothing to draw comes back as the explanation.
                    result is String -> view.showMessage(result)
                    else -> view.showMessage("No diagram for '$itemName'.")
                }
            }
        }
    }

    /**
     * The message the server sent, rather than the wrapper around it.
     *
     * A failed request arrives wrapped in a `CompletionException`, whose own message is the nested
     * exception's `toString` -- class name included. Unwrapping keeps the sentence the server
     * wrote, which is the part that tells the user what to do.
     */
    private fun rootCauseMessage(error: Throwable): String {
        var cause: Throwable = error
        while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
        return cause.message ?: "Could not fetch the diagram."
    }

    /**
     * The identifier under the caret, or `null` if there is none.
     *
     * Read straight from the document text: a Flix identifier is a letter or `_` followed by
     * letters, digits, `_` or `!`, and the caret may sit anywhere within one -- including at its
     * very end, which is where it lands after double-clicking the word.
     */
    private fun identifierAt(editor: Editor): String? =
        identifierAt(editor.document.charsSequence, editor.caretModel.offset)

    internal companion object {
        /** Matches the name the server advertises in `executeCommandProvider`. */
        const val COMMAND = "flix.showDiagram"

        /**
         * The identifier surrounding [caret] in [text], or `null` if there is none.
         *
         * Separated from the editor so the rule can be tested without one -- the interesting cases
         * are all about where the caret sits relative to the word, and none of them need a
         * platform fixture.
         */
        internal fun identifierAt(text: CharSequence, caret: Int): String? {
            val at = caret.coerceIn(0, text.length)

            var start = at
            while (start > 0 && isIdentifierChar(text[start - 1])) start--
            var end = at
            while (end < text.length && isIdentifierChar(text[end])) end++

            return if (end > start) text.subSequence(start, end).toString() else null
        }

        private fun isIdentifierChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '!'
    }
}
