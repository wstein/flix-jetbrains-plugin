package org.flixlang.intellij.lang

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.impl.DebugUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Drives the PSI through IntelliJ's real incremental-reparse path (typing/deleting in a live
 * editor, via [com.intellij.testFramework.fixtures.CodeInsightTestFixture]) rather than
 * one-shot [com.intellij.testFramework.ParsingTestCase] parses. This is the scenario that
 * actually matters for an editor plugin: [org.flixlang.intellij.lang.FlixParsingTest] and
 * [org.flixlang.intellij.lang.FlixErrorRecoveryTest] only ever hand the parser a complete
 * string, but a user's document is mutated one keystroke at a time, and incremental reparse is
 * a distinct code path (block-level re-lexing/re-parsing) from a fresh top-level parse -- it can
 * have its own bugs even when the single-shot parser is correct for the same final text.
 *
 * The fixture is configured with [FlixFileType] directly rather than by filename. Resolving a
 * filename to a file type goes through the plugin's `<fileType>` registration, and a
 * content-module descriptor is inert outside the assembled plugin, so `Test.flix` would resolve to
 * plain text and every assertion here would pass or fail for the wrong reason. Naming the type
 * explicitly keeps this test about incremental reparse; `FlixPluginDescriptorTest` covers the
 * registration.
 */
class FlixIncrementalEditTest : BasePlatformTestCase() {

    /**
     * Registers the parser definition for the fixture.
     *
     * Setting the file type alone is not enough: parsing is driven by
     * [LanguageParserDefinitions], populated from the `<lang.parserDefinition>` registration, which
     * a module-local fixture never reads. Without this the document has a Flix file type but no PSI
     * tree, so the error assertions below would silently pass or fail on an empty tree rather than
     * on incremental reparse -- exactly the false signal this test exists to avoid.
     */
    private lateinit var parserDefinition: FlixParserDefinition

    override fun setUp() {
        super.setUp()
        parserDefinition = FlixParserDefinition()
        LanguageParserDefinitions.INSTANCE.addExplicitExtension(FlixLanguage, parserDefinition)
    }

    override fun tearDown() {
        try {
            LanguageParserDefinitions.INSTANCE.removeExplicitExtension(FlixLanguage, parserDefinition)
        } finally {
            super.tearDown()
        }
    }

    private fun errorCount(): Int {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        println(DebugUtil.psiToString(myFixture.file, true, false))
        return PsiTreeUtil.findChildrenOfType(myFixture.file, PsiErrorElement::class.java).size
    }

    fun testTypingCompleteProgramCharacterByCharacter() {
        myFixture.configureByText(FlixFileType.INSTANCE, "")
        val program = """
            enum Shape {
                case Circle(Int32),
                case Square(Int32)
            }

            def area(s: Shape): Int32 = match s {
                case Shape.Circle(r) => r * r
                case Shape.Square(w) => w * w
            }
        """.trimIndent()

        // Now that FlixBraceMatcher/FlixQuoteHandler are registered, CodeInsightTestFixture.type()
        // exercises the real brace-aware typed handlers (auto-close pairs, smart/carried-over
        // indent on Enter) the same as an actual user's keystrokes would -- these didn't fire at
        // all before registration, since the platform had no brace context to key off for our
        // language. That's correct editor behavior, but it means literally retyping a
        // fully-formed snippet -- including its own indentation and closing braces -- races
        // against what the editor now auto-inserts, and can end up with duplicated braces or
        // compounding indentation, which is a fixture-vs-real-user-workflow mismatch (a real user
        // relies on those features instead of also typing the same content themselves), not an
        // incremental-reparse bug. Disabling them isolates this test back down to its actual
        // purpose -- exercising incremental lexing/parsing on a keystroke-by-keystroke basis --
        // without that confound.
        val settings = CodeInsightSettings.getInstance()
        val originalAutoBracket = settings.AUTOINSERT_PAIR_BRACKET
        val originalAutoQuote = settings.AUTOINSERT_PAIR_QUOTE
        val originalSmartIndent = settings.SMART_INDENT_ON_ENTER
        val originalInsertBraceOnEnter = settings.INSERT_BRACE_ON_ENTER
        val originalReformatOnRBrace = settings.REFORMAT_BLOCK_ON_RBRACE
        settings.AUTOINSERT_PAIR_BRACKET = false
        settings.AUTOINSERT_PAIR_QUOTE = false
        settings.SMART_INDENT_ON_ENTER = false
        settings.INSERT_BRACE_ON_ENTER = false
        settings.REFORMAT_BLOCK_ON_RBRACE = false
        try {
            myFixture.type(program)
        } finally {
            settings.AUTOINSERT_PAIR_BRACKET = originalAutoBracket
            settings.AUTOINSERT_PAIR_QUOTE = originalAutoQuote
            settings.SMART_INDENT_ON_ENTER = originalSmartIndent
            settings.INSERT_BRACE_ON_ENTER = originalInsertBraceOnEnter
            settings.REFORMAT_BLOCK_ON_RBRACE = originalReformatOnRBrace
        }
        PsiTreeUtil.findChildrenOfType(myFixture.file, com.intellij.psi.PsiElement::class.java) // force full traversal

        assertEquals(
            "Typing a complete, valid program character-by-character should converge to an " +
                "error-free tree, same as a one-shot parse of the same text",
            0,
            errorCount()
        )
    }

    fun testDeletingClosingBraceIntroducesThenClearsError() {
        myFixture.configureByText(
            FlixFileType.INSTANCE,
            """
            def foo(): Int32 = {
                1 + 1
            }
            """.trimIndent()
        )
        assertEquals("Valid starting document should parse cleanly", 0, errorCount())

        val closingBraceOffset = myFixture.editor.document.text.lastIndexOf('}')
        myFixture.editor.caretModel.moveToOffset(closingBraceOffset + 1)
        myFixture.type("\b") // backspace: delete the closing brace

        assertTrue(
            "Deleting the closing brace should leave at least one recognizable parse error",
            errorCount() > 0
        )

        myFixture.type("}") // retype it
        assertEquals(
            "Retyping the deleted brace should clear the error and match the original tree",
            0,
            errorCount()
        )
    }

    fun testTypingThroughATemporarilyIncompleteExpression() {
        myFixture.configureByText(FlixFileType.INSTANCE, "")
        // Simulates a user typing a binary expression left-to-right: after "def foo(): Int32 = 1 +"
        // the buffer is momentarily invalid (dangling operator) before "1" completes it. No
        // assertion mid-way other than "typing doesn't throw" -- CodeInsightTestFixture.type()
        // propagates any incremental-reparse exception as a test failure on its own.
        myFixture.type("def foo(): Int32 = 1 +")
        assertTrue(
            "Mid-typing, a dangling '+' should be a recognizable (not crashing) parse error",
            errorCount() > 0
        )
        myFixture.type(" 1")
        assertEquals("Completing the expression should clear the error", 0, errorCount())
    }

    fun testDeletingWholeBufferBackToEmpty() {
        myFixture.configureByText(FlixFileType.INSTANCE, "def foo(): Int32 = 123")
        myFixture.editor.selectionModel.setSelection(0, myFixture.editor.document.textLength)
        myFixture.type("\b")
        assertEquals("", myFixture.editor.document.text)
        assertEquals(
            "Deleting a document down to empty should not itself be treated as a parse error",
            0,
            errorCount()
        )
    }
}
