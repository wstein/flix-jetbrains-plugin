package dev.wstein.flixplugin.ui

import com.intellij.codeInsight.template.Template
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.ide.TitledHandler
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.rename.inplace.VariableInplaceRenameHandler
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
 * [VariableInplaceRenameHandler.isAvailable] wants a `PsiNamedElement` and a
 * `RefactoringSupportProvider` to find what to rewrite. The Flix PSI is a parse tree with neither,
 * deliberately: the compiler is the only semantic authority (ADR 0001), and resolving names in the
 * plugin would be a second Flix front end that disagrees with it silently.
 *
 * This handler does not need one. The server already knows every occurrence, so the ranges come
 * from `textDocument/references` and the template is built over those. Interaction in the IDE,
 * semantics in the compiler.
 *
 * ## Why it nevertheless extends [VariableInplaceRenameHandler]
 *
 * Not for behaviour -- every inherited step is overridden -- but because that base class is how
 * LSP4IJ recognises an in-place renamer and stands down. `LSPRenameHandler.isAvailableOnDataContext`
 * re-enters `RenameHandlerRegistry.getRenameHandlers` and answers `false` when every other available
 * handler `instanceof VariableInplaceRenameHandler`. Registering an unrelated `RenameHandler`
 * instead leaves two handlers available, and `RenameHandlerRegistry.getRenameHandler` then asks the
 * user which one they meant -- a *"What would you like to do?"* chooser in front of every rename.
 *
 * [FlixInplaceRenameHandlerTest] pins the base class for that reason.
 *
 * The platform's `isAvailableOnDataContext` is `final` and delegates to [isAvailable], which is why
 * that is the method overridden here.
 *
 * ## Why a symbol used in several files still gets the dialog
 *
 * A template edits the open document only. As soon as it does, the server's analysis of that file
 * is stale, so a follow-up `textDocument/rename` for the remaining files would be computed against
 * source that no longer exists. Renaming across files is therefore left to the dialog, which sends
 * one `textDocument/rename` and applies the whole edit atomically. Locals and parameters -- the
 * common case, and the one where a dialog is most intrusive -- are always single-file.
 */
class FlixInplaceRenameHandler : VariableInplaceRenameHandler(), TitledHandler {

    /**
     * Named for the chooser this handler exists to avoid.
     *
     * It is reachable only if some third rename handler is installed alongside this one and LSP4IJ,
     * and without it the chooser would offer `dev.wstein.flixplugin.ui.FlixInplaceRenameHandler@1f`.
     */
    override fun getActionTitle(): String = "Rename Flix symbol"

    /**
     * The caret sits on a Flix identifier, and the user has not turned in-place rename off.
     *
     * [element] is ignored: it is null for Flix, which is the whole reason this class exists.
     * Honouring the editor setting is not politeness -- returning `false` removes this handler from
     * the registry, LSP4IJ's stops standing down, and the rename falls back to its dialog, which is
     * exactly what a user who disabled in-place rename asked for.
     */
    override fun isAvailable(element: PsiElement?, editor: Editor, file: PsiFile): Boolean {
        if (file.virtualFile?.extension != FLIX_EXTENSION) return false
        if (!editor.settings.isVariableInplaceRenameEnabled) return false
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
                        if (ranges.isNullOrEmpty()) {
                            delegateToDialog(project, activeEditor, psiFile, dataContext)
                        } else {
                            startTemplate(project, activeEditor, ranges, oldName)
                        }
                    }
                }
            }
    }

    /**
     * Not reachable from the editor: this handler is offered only where there is a caret.
     *
     * The inherited implementation asserts a `PsiElement` it could rename, which Flix never has, so
     * it must not run.
     */
    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext) = Unit

    private fun delegateToDialog(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext?) {
        ApplicationManager.getApplication().invokeLater {
            LSPRenameHandler().invoke(project, editor, file, dataContext)
        }
    }

    /**
     * Replaces [ranges] with one template variable, so typing edits every occurrence at once.
     *
     * The template spans from the first occurrence to the last and reproduces the text between them
     * verbatim; only the occurrences become variable segments. Reformatting and indenting are off
     * because this rewrites identifiers inside existing code, and a template that reflowed the span
     * would turn a rename into an unrequested edit.
     */
    private fun startTemplate(project: Project, editor: Editor, ranges: List<TextRange>, oldName: String) {
        val document = editor.document
        val start = ranges.first().startOffset
        val end = ranges.last().endOffset

        val template = TemplateManager.getInstance(project).createTemplate("", "")
        template.isToReformat = false
        template.setToIndent(false)
        fillTemplate(template, document.charsSequence, ranges, oldName)

        WriteCommandAction.runWriteCommandAction(project, RENAME_COMMAND, null, {
            document.deleteString(start, end)
            editor.caretModel.moveToOffset(start)
            TemplateManager.getInstance(project).startTemplate(editor, template)
        })
    }

    private fun LogicalPosition.toLsp(): LspPosition = LspPosition(line, column)

    /** [locationsIn], converted to offsets in the open document and normalised. */
    private fun rangesIn(editor: Editor, locations: List<Location>, path: String): List<TextRange>? =
        locationsIn(locations, path)?.map { location ->
            TextRange(
                editor.logicalPositionToOffset(location.range.start.toLogical()),
                editor.logicalPositionToOffset(location.range.end.toLogical()),
            )
        }?.let { normalized(it) }

    private fun LspPosition.toLogical(): LogicalPosition = LogicalPosition(line, character)

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

        /**
         * [ranges] sorted, with any that overlaps one already kept dropped.
         *
         * A declaration and a reference can name the same span -- the server reports occurrences,
         * not distinct edits -- and two segments over one span would render the new name twice.
         */
        internal fun normalized(ranges: List<TextRange>): List<TextRange> {
            val kept = mutableListOf<TextRange>()
            for (range in ranges.sortedBy { it.startOffset }) {
                if (kept.isEmpty() || range.startOffset >= kept.last().endOffset) kept.add(range)
            }
            return kept
        }

        /**
         * Fills [template] so that each of [ranges] becomes the same variable and the source
         * between them is reproduced verbatim. [ranges] must be sorted and disjoint -- see
         * [normalized] -- and [text] is the whole document, so the offsets are absolute.
         *
         * The first occurrence gets **no** [Template.addVariableSegment] call. `addVariable` emits
         * that segment itself: `TemplateImpl.addVariable` calls `addVariableSegment(name)` whenever
         * the template has no text to parse, which a template built by
         * `TemplateManager.createTemplate(key, group)` never has -- that constructor calls
         * `setToParseSegments(false)`. Adding one as well put two variable segments at the same
         * offset, so the first occurrence rendered the new name twice.
         */
        internal fun fillTemplate(template: Template, text: CharSequence, ranges: List<TextRange>, oldName: String) {
            template.addVariable(VARIABLE, ConstantNode(oldName), ConstantNode(oldName), true)
            var cursor = ranges.first().endOffset
            for (range in ranges.drop(1)) {
                template.addTextSegment(text.subSequence(cursor, range.startOffset).toString())
                template.addVariableSegment(VARIABLE)
                cursor = range.endOffset
            }
        }
    }
}
