package org.flixlang.intellij.lang.editor

import com.intellij.application.options.CodeStyle
import com.intellij.lang.Language
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.project.Project
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.lineIndent.LineIndentProvider
import com.intellij.psi.tree.IElementType
import org.flixlang.intellij.lang.FlixLanguage
import org.flixlang.intellij.lang.psi.FlixTypes

/**
 * Where the caret lands after Enter.
 *
 * ## Why this extension point and not a formatter
 *
 * Pressing Enter in a `.flix` file put the caret in column 0, whatever it was typed after. Traced,
 * not guessed: `EnterHandler.DoEnterAction` inserts the newline and then asks
 * `CodeStyle.getLineIndent(editor, language, offset, allowDocCommit = false)`, which consults
 * `LineIndentProviderEP.findLineIndentProvider(language)` and **nothing else** when the document may
 * not be committed. Flix registered no provider, so the answer was always `null`, and a `null`
 * answer leaves the line alone.
 *
 * The platform's other route to an indent is a `FormattingModelBuilder`, and it is the wrong tool
 * here. A formatter is a description of how a whole file *should* be laid out -- it is what Reformat
 * Code runs -- so shipping one to fix Enter would mean shipping an opinion about every construct in
 * the language, and being wrong about any of them rewrites a user's file. This decides one thing:
 * where a new line starts.
 *
 * ## Why it reads tokens and not PSI
 *
 * `allowDocCommit = false` is the platform saying the PSI may be stale, and at this point it is: the
 * newline was inserted a few statements earlier in the same write action. So the source of truth is
 * the editor's own highlighter, which re-lexes on every document change and is therefore exactly as
 * current as the text. It also makes a string or a comment a single token, so a `{` inside either is
 * invisible to the scan below without any rule saying so.
 *
 * ## The rule
 *
 * One backward scan from the caret answers both halves:
 *
 * 1. the **enclosing unmatched opener** -- the `{`, `(`, `[`, `#{`, `#(` or `#|` this line is
 *    inside. The new line is indented one level past *that line's* indent, which is what makes
 *    nesting come out right without counting anything;
 * 2. the **last meaningful token**. When it is `=`, `=>`, `->` or `<-`, the line continues an
 *    unfinished construct and takes one level more -- `def f(): Int32 =` and `case Some(s) =>` both
 *    end a line in ordinary Flix and both want their body indented.
 *
 * A line that *starts* with the opener's own closer is aligned with the opener instead, so Enter
 * between `{` and `}` leaves the brace where it belongs rather than one level in.
 *
 * ## What it deliberately does not do
 *
 * The indent of the line *before* the caret is never copied. It is the obvious rule and it is worse:
 * after
 *
 * ```flix
 * let a =
 *     compute();
 * ```
 *
 * copying gives the next statement the continuation's indent, while the enclosing opener gives it
 * the block's. The one place copying wins is a `case` arm following an indented body, where both
 * rules over-indent and the reader outdents by hand; the platform's own languages behave the same
 * without a formatter to re-indent on the keyword.
 *
 * ## Cost
 *
 * The scan is linear in tokens before the caret, and at top level -- where there is no enclosing
 * opener -- that is the whole file. Each step is an index decrement over the highlighter's segment
 * array rather than a re-lex, and it happens once per Enter, so the bound that would avoid it would
 * cost more in explanation than it saves in time.
 */
class FlixLineIndentProvider : LineIndentProvider {

    override fun isSuitableFor(language: Language?): Boolean = language == FlixLanguage

    override fun getLineIndent(project: Project, editor: Editor, language: Language?, offset: Int): String? {
        if (editor !is EditorEx) {
            return null
        }
        val document = editor.document
        if (offset < 0 || offset > document.textLength) {
            return null
        }

        val highlighter = editor.highlighter
        val context = scanBack(highlighter, offset)
        val unit = indentUnit(project, document)

        val opener = context.opener
        if (opener == null) {
            // Nothing encloses the caret, so the line belongs at the left margin -- unless it
            // continues the declaration above it, which is how a Flix definition without a block is
            // written.
            return if (context.last in Continuations) unit else ""
        }

        val openerIndent = indentAt(document, opener.offset)
        // A line beginning with this opener's own closer belongs *with the opener*, not inside it.
        // Matched rather than merely "some closer": in half-written code a `)` may well be the first
        // thing on a line inside a block, and de-indenting it would move a brace nobody typed.
        if (firstTokenOnLine(highlighter, document, offset) == Openers[opener.type]) {
            return openerIndent
        }
        return if (context.last in Continuations) openerIndent + unit + unit else openerIndent + unit
    }

    /** The enclosing unmatched opener and the last meaningful token, from one pass. */
    private data class Context(val opener: Opener?, val last: IElementType?)

    private data class Opener(val offset: Int, val type: IElementType)

    /**
     * Walks back from [offset] to the opener that encloses it.
     *
     * Depth-counted rather than kind-matched, because a nesting that mixes kinds is broken code and
     * the answer for broken code only has to be stable. Which kind was found is still reported, so
     * the closer that would align with it can be recognised.
     */
    private fun scanBack(highlighter: EditorHighlighter, offset: Int): Context {
        if (offset <= 0) {
            return Context(null, null)
        }
        val iterator = highlighter.createIterator(offset - 1)
        var depth = 0
        var last: IElementType? = null
        while (!iterator.atEnd()) {
            val type = iterator.tokenType
            if (type != null && type !in Ignored) {
                if (last == null) {
                    last = type
                }
                if (type in Closers) {
                    depth++
                } else if (Openers.containsKey(type)) {
                    if (depth == 0) {
                        return Context(Opener(iterator.start, type), last)
                    }
                    depth--
                }
            }
            iterator.retreat()
        }
        return Context(null, last)
    }

    /** The leading whitespace of the line holding [offset], copied rather than rebuilt. */
    private fun indentAt(document: Document, offset: Int): String {
        val start = document.getLineStartOffset(document.getLineNumber(offset))
        val text = document.charsSequence
        var end = start
        while (end < offset && (text[end] == ' ' || text[end] == '\t')) {
            end++
        }
        return text.subSequence(start, end).toString()
    }

    /** The first token on the line holding [offset], or `null` if the line has none. */
    private fun firstTokenOnLine(highlighter: EditorHighlighter, document: Document, offset: Int): IElementType? {
        val line = document.getLineNumber(offset)
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        val text = document.charsSequence
        var at = start
        while (at < end && (text[at] == ' ' || text[at] == '\t')) {
            at++
        }
        if (at >= end) {
            return null
        }
        val iterator = highlighter.createIterator(at)
        return if (iterator.atEnd()) null else iterator.tokenType
    }

    /**
     * One level of indent, as the project's settings define it.
     *
     * Flix has no code-style page of its own, so these are the platform's defaults -- four spaces,
     * which is what Flix is written in. Read here rather than fixed, so a user who changes them is
     * obeyed the moment they do.
     */
    private fun indentUnit(project: Project, document: Document): String {
        val options = CodeStyle.getIndentOptions(project, document)
        return if (options.USE_TAB_CHARACTER) "\t" else " ".repeat(options.INDENT_SIZE.coerceAtLeast(1))
    }

    private companion object {

        /** Every delimiter that opens something, and the delimiter that closes it. */
        val Openers: Map<IElementType, IElementType> = mapOf(
            FlixTypes.CURLY_L to FlixTypes.CURLY_R,
            FlixTypes.PAREN_L to FlixTypes.PAREN_R,
            FlixTypes.BRACKET_L to FlixTypes.BRACKET_R,
            // The fixpoint and schema forms open with a token of their own and close with an
            // ordinary one, as `FlixBraceMatcher` also records.
            FlixTypes.HASH_CURLY_L to FlixTypes.CURLY_R,
            FlixTypes.HASH_PAREN_L to FlixTypes.PAREN_R,
            FlixTypes.HASH_BAR to FlixTypes.BAR_HASH,
        )

        val Closers: Set<IElementType> = Openers.values.toSet()

        /**
         * Tokens a line can end on with the construct unfinished.
         *
         * All of them are ordinary line endings in Flix: `def f(): Int32 =`, `case Some(s) =>`,
         * a lambda's `x ->` before a body too long for the line, and `<-` in a `forM`. What follows
         * belongs one level in.
         */
        val Continuations: Set<IElementType> = setOf(
            FlixTypes.EQUAL,
            FlixTypes.ARROW_THICK_R,
            FlixTypes.ARROW_THIN_R,
            FlixTypes.ARROW_THIN_R_TIGHT,
            FlixTypes.ARROW_THIN_L,
        )

        /**
         * What the scan steps over.
         *
         * Comments because they are not part of the construct being written, and whitespace because
         * that is what the caret is sitting in.
         */
        val Ignored: Set<IElementType> = setOf(
            TokenType.WHITE_SPACE,
            FlixTypes.COMMENT_LINE,
            FlixTypes.COMMENT_BLOCK,
            FlixTypes.COMMENT_DOC,
        )
    }
}
