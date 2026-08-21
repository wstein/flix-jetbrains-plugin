package de.wstein.flixplugin.debugger

import com.intellij.debugger.SourcePosition
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import org.flixlang.intellij.lang.FlixFileType
import org.flixlang.intellij.lang.psi.FlixDefDecl

/**
 * Which Flix definition a source position sits in.
 *
 * This is what lets Step Over mean "finish this line and stop at the next one **in this function**"
 * rather than "stop at the next Flix line anywhere".
 *
 * ## Why the answer comes from the PSI and not the bytecode
 *
 * The obvious source would be the generated class name, and it does not work: the name encodes the
 * **entry point**, not the definition. In `flix-lab`, `Clo$main$399829` is compiled from
 * `Main.flix`, `Clo$main$399824` from `Nec.flix` and `Clo$main$399833` from `Sys/Env.flix` -- every
 * closure reachable from `main` is named `Clo$main$…`, whether it is `main`'s own code or a library
 * function it called. Nothing in the class file distinguishes them.
 *
 * The IDE does not need it to. A resolved [SourcePosition] is a file and a line, and the Flix PSI
 * already says which `def` encloses that line. That makes this a question the plugin can answer on
 * its own -- no compiler change, no side table, no new bytecode attribute.
 *
 * Its limits follow from being a *source-level* notion, and both are deliberate:
 *
 *  - **Recursion is not distinguished.** A recursive call re-enters the same definition, so a step
 *    over one stops inside it. Separating those needs a per-activation identity, which is a runtime
 *    notion rather than a source one.
 *  - **Inlined code is attributed to where it was written.** The fork's inliner keeps the callee's
 *    own `SourceLocation` when it substitutes a body, so inlined library code still reports its own
 *    file and line and is correctly seen as a different definition.
 */
internal object FlixDefinitionScope {

    /**
     * A stable identity for the Flix definition containing [position], or `null` if there is none.
     *
     * The identity is the file plus the declaration's start offset rather than its name: two `def`s
     * in one file can share a name across namespaces, and a name alone would merge them into one
     * scope and stop a step early.
     */
    fun keyOf(position: SourcePosition?): String? {
        val sourcePosition = position ?: return null
        if (sourcePosition.file.fileType != FlixFileType.INSTANCE) return null

        return ReadAction.computeBlocking<String?, RuntimeException> {
            keyAt(sourcePosition.file, sourcePosition.line, sourcePosition.elementAt)
        }
    }

    /**
     * The identity of the `def` covering [line] of [file], or `null` if the line is not inside one.
     *
     * Split from [keyOf] so the rule can be exercised against real parsed Flix without a live debug
     * session: `SourcePosition` needs a project and a document manager, and the decision being made
     * here needs neither.
     *
     * @param elementAt the position's own element when the caller already has it
     */
    internal fun keyAt(file: PsiFile, line: Int, elementAt: PsiElement? = null): String? {
        val element = elementAt ?: firstMeaningfulElementOnLine(file, line) ?: return null
        // `false` -- consider the element itself, not only its ancestors, so a position resolving
        // directly to the declaration still finds it.
        val declaration = PsiTreeUtil.getParentOfType(element, FlixDefDecl::class.java, false)
            ?: return null
        val path = file.virtualFile?.path ?: file.name
        return "$path#${declaration.textRange.startOffset}"
    }

    /**
     * The first non-whitespace element on [line].
     *
     * `SourcePosition.getElementAt` returns `null` for a line that begins with whitespace, which is
     * every line inside a `def` body -- exactly the lines a step lands on. Without this the scope
     * would be unknown for indented code and Step Over would silently degrade to Step Into.
     */
    private fun firstMeaningfulElementOnLine(file: PsiFile, line: Int): PsiElement? {
        val document = file.viewProvider.document ?: return null
        if (line < 0 || line >= document.lineCount) return null

        val lineEnd = document.getLineEndOffset(line)
        var offset = document.getLineStartOffset(line)
        while (offset < lineEnd) {
            val candidate = file.findElementAt(offset)
            if (candidate != null && candidate !is PsiWhiteSpace) return candidate
            offset = candidate?.textRange?.endOffset?.coerceAtLeast(offset + 1) ?: (offset + 1)
        }
        return null
    }
}
