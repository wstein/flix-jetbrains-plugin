package org.flixlang.intellij.lang.editor

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.psi.codeStyle.lineIndent.LineIndentProvider
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.flixlang.intellij.lang.FlixFileType
import org.flixlang.intellij.lang.FlixLanguage
import org.flixlang.intellij.lang.FlixParserDefinition
import org.flixlang.intellij.lang.highlighting.FlixSyntaxHighlighterFactory

/**
 * Where the caret lands after Enter.
 *
 * ## Driven through the real key press
 *
 * `myFixture.performEditorAction(ACTION_EDITOR_ENTER)` runs the platform's own `EnterHandler`, which
 * is the only caller that matters: it inserts the newline and *then* asks for an indent, with the
 * document uncommitted. Calling [FlixLineIndentProvider.getLineIndent] directly would skip the two
 * things most likely to be wrong -- whether the platform asks this plugin at all, and whether the
 * offset it asks about is the one this assumes.
 *
 * ## Three registrations, all of them load-bearing
 *
 * A module-local fixture never reads the content-module descriptor, so each is made by hand and each
 * silently changes the answer if it is missing:
 *
 * - the **parser definition**, or a `.flix` document is plain text;
 * - the **syntax highlighter**, or the editor's highlighter tokenises nothing and the backward scan
 *   sees one span of plain text with no braces in it;
 * - the **provider itself**, on an extension point with no language attribute -- `isSuitableFor`
 *   does the keying, which is why a test of it is a test of that method too.
 */
class FlixLineIndentProviderTest : BasePlatformTestCase() {

    private lateinit var parserDefinition: FlixParserDefinition
    private lateinit var highlighterFactory: FlixSyntaxHighlighterFactory

    override fun setUp() {
        super.setUp()
        parserDefinition = FlixParserDefinition()
        LanguageParserDefinitions.INSTANCE.addExplicitExtension(FlixLanguage, parserDefinition)
        highlighterFactory = FlixSyntaxHighlighterFactory()
        SyntaxHighlighterFactory.LANGUAGE_FACTORY.addExplicitExtension(FlixLanguage, highlighterFactory)
        ExtensionTestUtil.maskExtensions(
            // Named rather than taken from a constant: `LineIndentProviderEP` keeps the only one and
            // keeps it private, so the string is the API. `FlixPluginDescriptorTest` checks that the
            // descriptor registers under the same name.
            ExtensionPointName.create<LineIndentProvider>("com.intellij.lineIndentProvider"),
            listOf(FlixLineIndentProvider()),
            testRootDisposable,
        )
        // Off, so what is asserted is this provider's answer rather than the platform's brace and
        // quote completion racing it. `FlixIncrementalEditTest` disables the same settings for the
        // same reason.
        CodeInsightSettings.getInstance().AUTOINSERT_PAIR_BRACKET = false
    }

    override fun tearDown() {
        try {
            CodeInsightSettings.getInstance().AUTOINSERT_PAIR_BRACKET = true
            SyntaxHighlighterFactory.LANGUAGE_FACTORY.removeExplicitExtension(FlixLanguage, highlighterFactory)
            LanguageParserDefinitions.INSTANCE.removeExplicitExtension(FlixLanguage, parserDefinition)
        } finally {
            super.tearDown()
        }
    }

    /** The text after pressing Enter at `<caret>`. */
    private fun afterEnter(source: String): String {
        myFixture.configureByText(FlixFileType.INSTANCE, source)
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)
        return myFixture.editor.document.text
    }

    /** What the caret's line holds after Enter, leading whitespace included. */
    private fun lineAfterEnter(source: String): String {
        myFixture.configureByText(FlixFileType.INSTANCE, source)
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)
        val document = myFixture.editor.document
        val line = document.getLineNumber(myFixture.editor.caretModel.offset)
        return document.getText(
            com.intellij.openapi.util.TextRange(
                document.getLineStartOffset(line),
                document.getLineEndOffset(line),
            ),
        )
    }

    fun testEnterAfterAnOpeningBraceIndentsOneLevel() {
        // The report. Nothing indented at all, because the platform asks a `LineIndentProvider` and
        // Flix registered none.
        assertEquals("    ", lineAfterEnter("def f(): Int32 = {<caret>"))
    }

    fun testEnterInsideABlockKeepsTheBlocksIndent() {
        // Not the previous line's indent -- the enclosing opener's, plus one. The two agree here and
        // disagree in `testEnterAfterAContinuationInsideABlock`.
        assertEquals("    ", lineAfterEnter("def f(): Int32 = {\n    let a = 1;<caret>\n}"))
    }

    fun testEnterNestedIndentsFromTheInnermostOpener() {
        val source = "def f(x: Int32): Int32 = {\n    match x {\n        case _ => 1<caret>\n    }\n}"

        assertEquals("        ", lineAfterEnter(source))
    }

    fun testEnterAfterAClosingBraceReturnsToTheOuterLevel() {
        // The `}` closes the inner block, so the caret is back inside the outer one. A rule that
        // copied the previous line would give this the closing brace's own indent, which is right
        // here only by coincidence -- `testEnterAfterTheLastClosingBrace` is where it diverges.
        assertEquals("    ", lineAfterEnter("def f(): Int32 = {\n    if (true) {\n        1\n    }<caret>\n}"))
    }

    fun testEnterAfterTheLastClosingBraceReturnsToTheMargin() {
        assertEquals("", lineAfterEnter("def f(): Int32 = {\n    1\n}<caret>"))
    }

    fun testEnterAfterATrailingEqualsIndentsTheBody() {
        // A Flix definition needs no block, and this is how one without a block is written.
        assertEquals("    ", lineAfterEnter("def f(): Int32 =<caret>"))
    }

    fun testEnterAfterAMatchArmIndentsItsBody() {
        val source = "def f(x: Int32): Int32 = {\n    match x {\n        case _ =><caret>\n    }\n}"

        assertEquals("            ", lineAfterEnter(source))
    }

    fun testEnterAfterAContinuationInsideABlockAddsToTheBlocksIndent() {
        // Two levels: one for the block, one for the unfinished `let`. A rule that only copied the
        // previous line's indent would give one.
        assertEquals("        ", lineAfterEnter("def f(): Int32 = {\n    let a =<caret>\n}"))
    }

    fun testTheStatementAfterAContinuationReturnsToTheBlocksIndent() {
        // The case that decides the whole design. Copying the previous line's indent would put this
        // statement under the continuation, at eight; the enclosing opener puts it back in the block
        // where it belongs.
        assertEquals("    ", lineAfterEnter("def f(): Int32 = {\n    let a =\n        1;<caret>\n}"))
    }

    fun testEnterBetweenBracesPutsTheClosingBraceOnItsOwnLine() {
        // Three lines out of one keystroke. The platform's own `EnterBetweenBracesHandler` moves the
        // `}` down and indents it; this provider supplies the caret's line. Measured, after asserting
        // otherwise: removing the closer rule below leaves this unchanged, so the brace's own column
        // is not this plugin's answer here. `testEnterBeforeAClosingBrace` is where it is.
        assertEquals("def f(): Int32 = {\n    \n}", afterEnter("def f(): Int32 = {<caret>}"))
    }

    fun testEnterBeforeAClosingBraceLeavesItUnderTheOpener() {
        // The closer rule, reached directly. `EnterBetweenBracesHandler` needs the two braces to be
        // adjacent, so with whitespace between them it does not fire and the `}` arrives here on a
        // line of its own -- where aligning it with its opener is the whole of the answer.
        assertEquals("def f(): Int32 = {\n    \n}", afterEnter("def f(): Int32 = {\n    <caret>}"))
    }

    fun testAClosingBraceOfANestedBlockAlignsWithItsOwnOpener() {
        val source = "def f(): Int32 = {\n    if (true) {\n        1\n        <caret>}\n}"

        // The `}` moves from column 8 to column 4, where its own opener is, while the caret's line
        // stays inside the block at 8.
        assertEquals("def f(): Int32 = {\n    if (true) {\n        1\n        \n    }\n}", afterEnter(source))
    }

    fun testAClosingBraceOfAnotherKindIsNotAlignedWith() {
        // `)` does not close `{`. Aligning on any closer would move a paren that half-written code
        // left at the start of a line inside a block.
        assertEquals("def f(): Int32 = {\n    )", afterEnter("def f(): Int32 = {<caret>)"))
    }

    fun testACommentAfterAContinuationDoesNotHideIt() {
        // The scan steps over comments, so a trailing note does not turn an unfinished definition
        // into a finished one. Without that, the last token before the caret is the comment and the
        // body lands at the margin.
        assertEquals("    ", lineAfterEnter("def f(): Int32 = // to do<caret>"))
    }

    fun testACommentBetweenTokensIsNotCountedAsCode() {
        assertEquals("    ", lineAfterEnter("def f(): Int32 = {\n    let a = 1; /* note */<caret>\n}"))
    }

    fun testANestedRecordIndentsFromTheBraceItIsInside() {
        // Three openers deep, and only the innermost decides. The caret is at the end of its line so
        // that this is the indent rule answering rather than the closer rule --
        // `testANestedRecordsClosingBraceAlignsWithItsOwnOpener` covers the other way round.
        val source = "def f(): Int32 = {\n    let r = {\n        inner = {<caret>\n    };\n    1\n}"

        assertEquals("            ", lineAfterEnter(source))
    }

    fun testANestedRecordsClosingBraceAlignsWithItsOwnOpener() {
        // The inner record's `}`, not the block's: the scan stops at the first unmatched opener, so
        // the brace lands under `inner = {` rather than under the `let` or the definition.
        val source = "def f(): Int32 = {\n    let r = {\n        inner = {\n            deep = 1\n     <caret>};\n    1\n}"

        assertTrue(
            "the closing brace did not align with its own opener: ${afterEnter(source)}",
            afterEnter(source).contains("\n        };"),
        )
    }

    fun testARecordLiteralOpensABlockLikeAnythingElse() {
        // `{ ... }` is a record here rather than a block, and the indent does not depend on knowing
        // which: the rule is structural, so one `{` behaves like the next.
        assertEquals("        ", lineAfterEnter("def f(): Int32 = {\n    let r = {<caret>\n}"))
    }

    fun testABraceInsideAnInterpolatedStringDoesNotOpenABlock() {
        // The one string that could plausibly tokenise as several: `"${n}"` has a brace in it, and a
        // lexer that emitted the interpolation as its own tokens would leave an opener behind.
        assertEquals("", lineAfterEnter("""def f(n: Int32): String = "${'$'}{n}"<caret>"""))
    }

    fun testABraceInsideAStringDoesNotOpenABlock() {
        // The lexer makes a string one token, so the scan never sees what is in it. Asserted because
        // a scan over characters rather than tokens is the obvious way to write this and would count
        // the brace.
        assertEquals("", lineAfterEnter("""def f(): String = "{"<caret>"""))
    }

    fun testABraceInsideACommentDoesNotOpenABlock() {
        assertEquals("", lineAfterEnter("def f(): Int32 = 1 // {<caret>"))
    }

    fun testTheIndentIsTheProjectsAndNotAConstant() {
        // Read from the code style settings on every answer, so a user who changes them is obeyed
        // without reopening anything.
        val settings = com.intellij.application.options.CodeStyle.getSettings(project)
        val options = settings.getIndentOptions(FlixFileType.INSTANCE)
        val original = options.INDENT_SIZE
        options.INDENT_SIZE = 2
        try {
            assertEquals("  ", lineAfterEnter("def f(): Int32 = {<caret>"))
        } finally {
            options.INDENT_SIZE = original
        }
    }

    fun testAnotherLanguageIsNotThisProvidersBusiness() {
        // The extension point has no language attribute, so this method is the only thing keeping
        // the provider off every other file in the IDE.
        val provider = FlixLineIndentProvider()

        assertTrue(provider.isSuitableFor(FlixLanguage))
        assertFalse(provider.isSuitableFor(com.intellij.lang.Language.ANY))
        assertFalse(provider.isSuitableFor(null))
    }
}
