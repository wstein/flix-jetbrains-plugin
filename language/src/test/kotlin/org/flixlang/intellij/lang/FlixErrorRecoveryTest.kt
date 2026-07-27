package org.flixlang.intellij.lang

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase
import org.flixlang.intellij.lang.psi.FlixTypes

/**
 * Error-recovery tests for intentionally malformed / mid-edit-shaped Flix source.
 *
 * For an editor plugin, a user's buffer is in some incomplete or momentarily-broken state far
 * more often than it exercises a rare grammar corner (JVM interop, fixpoint provenance, ...), so
 * these tests assert two things instead of "parses cleanly":
 *   1. The parser never throws on malformed input -- it degrades to at least one PsiErrorElement.
 *   2. Recovery is good enough that an unrelated, well-formed declaration *after* the malformed
 *      one is still recognized as such (its name is lexed/parsed as an identifier, not swallowed
 *      into the error's recovery blob) -- one bad declaration shouldn't poison the whole file.
 */
class FlixErrorRecoveryTest : ParsingTestCase("", "flix", FlixParserDefinition()) {
    override fun getTestDataPath(): String = ""

    override fun skipSpaces(): Boolean = true

    private fun parseWithoutCrashing(code: String): PsiFile {
        val psiFile = createPsiFile("test", code)
        ensureParsed(psiFile)
        println(toParseTreeText(psiFile, true, false))
        return psiFile
    }

    private fun assertHasParseError(code: String) {
        val psiFile = parseWithoutCrashing(code)
        val error = PsiTreeUtil.findChildOfType(psiFile, PsiErrorElement::class.java)
        assertNotNull("Expected malformed input to produce a PsiErrorElement, found none", error)
    }

    /** Builds `malformed` followed by a distinct, well-formed `def <name>(): Int32 = 1` and
     *  asserts that second declaration's name still shows up as a real identifier token, proving
     *  the parser recovered rather than treating the rest of the file as unparseable garbage. */
    private fun assertRecoversAndContinues(malformed: String, followingDeclName: String) {
        val code = "$malformed\n\ndef $followingDeclName(): Int32 = 1"
        val psiFile = parseWithoutCrashing(code)
        val identLeaf = PsiTreeUtil.findChildrenOfType(psiFile, PsiElement::class.java)
            .firstOrNull { it.node?.elementType == FlixTypes.NAME_LOWERCASE && it.text == followingDeclName }
        assertNotNull(
            "Expected '$followingDeclName' to still be recognized as an identifier after recovering " +
                "from the preceding malformed declaration",
            identLeaf
        )
    }

    fun testUnclosedEnumBrace() {
        assertHasParseError(
            """
            enum Shape {
                case Circle(Int32)
            """.trimIndent()
        )
    }

    fun testUnclosedEnumBraceRecovers() {
        assertRecoversAndContinues(
            """
            enum Shape {
                case Circle(Int32)
            """.trimIndent(),
            "afterBrokenEnum"
        )
    }

    fun testIncompleteMatchArm() {
        assertHasParseError(
            """
            def area(s: Shape): Int32 = match s {
                case Shape.Circle(r) =>
            }
            """.trimIndent()
        )
    }

    fun testIncompleteMatchArmRecovers() {
        assertRecoversAndContinues(
            """
            def area(s: Shape): Int32 = match s {
                case Shape.Circle(r) =>
            }
            """.trimIndent(),
            "afterBrokenMatch"
        )
    }

    fun testDanglingBinaryOperator() {
        assertHasParseError("def foo(): Int32 = 1 +")
    }

    fun testDanglingBinaryOperatorRecovers() {
        assertRecoversAndContinues("def foo(): Int32 = 1 +", "afterDanglingOp")
    }

    fun testUnterminatedString() {
        assertHasParseError("""def foo(): String = "hello""")
    }

    fun testUnterminatedStringRecovers() {
        assertRecoversAndContinues("""def foo(): String = "hello""", "afterUnterminatedString")
    }

    fun testMissingDefBody() {
        assertHasParseError("def foo(): Int32 =")
    }

    fun testMissingDefBodyRecovers() {
        assertRecoversAndContinues("def foo(): Int32 =", "afterMissingBody")
    }

    fun testUnclosedParenInCall() {
        assertHasParseError("def foo(): Int32 = bar(1, 2")
    }

    fun testUnclosedParenInCallRecovers() {
        assertRecoversAndContinues("def foo(): Int32 = bar(1, 2", "afterUnclosedParen")
    }

    fun testMismatchedBrackets() {
        assertHasParseError("def foo(): List[Int32 = Nil")
    }

    fun testUnclosedBlockComment() {
        assertHasParseError(
            """
            def foo(): Int32 = 1
            /* this comment never closes
            """.trimIndent()
        )
    }

    fun testEmptyFile() {
        // Not malformed, but the degenerate edge case every incremental-typing session starts
        // from (the buffer right after a document is created / fully deleted).
        val psiFile = parseWithoutCrashing("")
        val error = PsiTreeUtil.findChildOfType(psiFile, PsiErrorElement::class.java)
        assertNull("An empty file should not itself be a parse error", error)
    }

    fun testOnlyWhitespace() {
        val psiFile = parseWithoutCrashing("   \n\n\t  ")
        val error = PsiTreeUtil.findChildOfType(psiFile, PsiErrorElement::class.java)
        assertNull("Whitespace-only content should not be a parse error", error)
    }

    fun testJustAKeywordPrefix() {
        // The exact shape of "user has typed the first few characters of a declaration keyword."
        assertHasParseError("de")
    }
}
