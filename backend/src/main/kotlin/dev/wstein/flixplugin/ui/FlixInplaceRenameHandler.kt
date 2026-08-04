package dev.wstein.flixplugin.ui

import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.rename.RenameHandler
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.features.rename.LSPRenameHandler
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import java.net.URI
import org.eclipse.lsp4j.Position as LspPosition

/**
 * Renames a Flix symbol in place, editing every occurrence at once, instead of through a dialog.
 *
 * ## Why this is not the platform's own in-place rename
 *
 * `VariableInplaceRenameHandler` wants a `PsiNamedElement` and `PsiReference`s to find what to
 * rewrite. The Flix PSI is a parse tree with neither, deliberately: the compiler is the only
 * semantic authority (ADR 0001), and resolving names in the plugin would be a second Flix front end
 * that disagrees with it silently.
 *
 * It does not need one. The server already knows every occurrence, so the ranges come from
 * `textDocument/references` and the template is built over those. Interaction in the IDE, semantics
 * in the compiler.
 *
 * ## Why LSP4IJ's handler is not simply configured
 *
 * It has no in-place mode -- no class, no setting. `LSPRenameHandler` always opens a modal dialog.
 * This handler is registered ahead of it and delegates back for the case it cannot serve.
 *
 * ## Why a symbol used in several files still gets the dialog
 *
 * A template edits the open document only. As soon as it does, the server's analysis of that file
 * is stale, so a follow-up `textDocument/rename` for the remaining files would be computed against
 * source that no longer exists. Renaming across files is therefore left to the dialog, which sends
 * one `textDocument/rename` and applies the whole edit atomically. Locals and parameters -- the
 * common case, and the one where a dialog is most intrusive -- are always single-file.
 */
class FlixInplaceRenameHandler : RenameHandler {

    override fun isAvailableOnDataContext(dataContext: DataContext): Boolean {
        val psiFile = CommonDataKeys.PSI_FILE.getData(dataContext) ?: return false
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return false
        if (psiFile.virtualFile?.extension != FLIX_EXTENSION) return false
        return FlixShowDiagramAction.identifierAt(editor.document.charsSequence, editor.caretModel.offset) != null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext?) {
        val psiFile = file ?: return
        val activeEditor = editor ?: return
        val path = psiFile.virtualFile?.path ?: return
        val document = activeEditor.document
        val caret = activeEditor.caretModel.offset
        val oldName = FlixShowDiagramAction.identifierAt(document.charsSequence, caret) ?: return

        val params = ReferenceParams(
            TextDocumentIdentifier(psiFile.virtualFile.url),
            activeEditor.offsetToLogicalPosition(caret).toLsp(),
            ReferenceContext(true),
        )

        LanguageServerManager.getInstance(project)
            .getLanguageServer(FlixShowDiagramAction.SERVER_ID)
            .whenComplete { item, error ->
                if (error != null || item == null) {
                    delegateToDialog(project, activeEditor, psiFile, dataContext)
                    return@whenComplete
                }
                item.server.textDocumentService.references(params).whenComplete { locations, refError ->
                    ApplicationManager.getApplication().invokeLater {
                        val ranges = if (refError != null) null
                        else rangesIn(activeEditor, locations.orEmpty(), path)
                        if (ranges == null || ranges.isEmpty()) {
                            delegateToDialog(project, activeEditor, psiFile, dataContext)
                        } else {
                            startTemplate(project, activeEditor, ranges, oldName)
                        }
                    }
                }
            }
    }

    /** Not reachable from the editor: this handler is offered only where there is a caret. */
    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) = Unit

    private fun delegateToDialog(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext?) {
        ApplicationManager.getApplication().invokeLater {
            LSPRenameHandler().invoke(project, editor, file, dataContext)
        }
    }

    /**
     * Replaces [ranges] with one template variable, so typing edits every occurrence at once.
     *
     * The template spans from the first occurrence to the last and reproduces the text between
     * them verbatim; only the occurrences become variable segments. Reformatting and indenting are
     * off because this rewrites identifiers inside existing code, and a template that reflowed the
     * span would turn a rename into an unrequested edit.
     */
    private fun startTemplate(project: Project, editor: Editor, ranges: List<TextRange>, oldName: String) {
        val document = editor.document
        val start = ranges.first().startOffset
        val end = ranges.last().endOffset

        val template = TemplateManager.getInstance(project).createTemplate("", "")
        template.isToReformat = false
        template.setToIndent(false)
        template.addVariable(VARIABLE, ConstantNode(oldName), ConstantNode(oldName), true)

        var cursor = start
        for (range in ranges) {
            if (range.startOffset > cursor) {
                template.addTextSegment(document.getText(TextRange(cursor, range.startOffset)))
            }
            template.addVariableSegment(VARIABLE)
            cursor = range.endOffset
        }

        WriteCommandAction.runWriteCommandAction(project, RENAME_COMMAND, null, {
            document.deleteString(start, end)
            editor.caretModel.moveToOffset(start)
            TemplateManager.getInstance(project).startTemplate(editor, template)
        })
    }

    private fun LogicalPosition.toLsp(): LspPosition = LspPosition(line, column)

    companion object {
        private const val FLIX_EXTENSION = "flix"
        private const val VARIABLE = "FlixRenamed"
        private const val RENAME_COMMAND = "Rename Flix Symbol"

        /**
         * The occurrences that lie in [path], or `null` if any occurrence lies elsewhere.
         *
         * `null` rather than a filtered list on purpose: a partial in-place rename would rewrite
         * this file and silently leave the others behind. Deciding it here keeps the rule testable
         * without an editor.
         */
        internal fun locationsIn(locations: List<Location>, path: String): List<Location>? {
            if (locations.isEmpty()) return null
            val (here, elsewhere) = locations.partition { pathOf(it.uri) == path }
            return if (elsewhere.isEmpty()) here else null
        }

        /** The filesystem path a `file:` URI names, or the string itself if it is not one. */
        internal fun pathOf(uri: String): String =
            runCatching { URI(uri).path }.getOrNull() ?: uri
    }

    /** [locationsIn], converted to offsets in the open document and sorted by position. */
    private fun rangesIn(editor: Editor, locations: List<Location>, path: String): List<TextRange>? =
        locationsIn(locations, path)?.map { location ->
            TextRange(
                editor.logicalPositionToOffset(location.range.start.toLogical()),
                editor.logicalPositionToOffset(location.range.end.toLogical()),
            )
        }?.sortedBy { it.startOffset }

    private fun LspPosition.toLogical(): LogicalPosition = LogicalPosition(line, character)
}
