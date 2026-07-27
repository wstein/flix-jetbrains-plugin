package org.flixlang.intellij.lang

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import org.flixlang.intellij.lang.psi.FlixDefDecl
import org.flixlang.intellij.run.nameOrNull
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

    /**
     * Builds `malformed` followed by a well-formed `def <name>(): Int32 = 1` and asserts the second
     * declaration survives as a real [FlixDefDecl] outside any error element.
     *
     * Asserting on a NAME_LOWERCASE leaf instead -- which an earlier version of this helper did --
     * proves nothing: the lexer produces tokens regardless, so the identifier is still present
     * inside a PsiErrorElement that has swallowed the entire rest of the file. That is precisely
     * the failure mode `recoverWhile` exists to prevent, and the weaker assertion reported it as
     * recovery.
     */
    private fun assertRecoversAndContinues(malformed: String, followingDeclName: String) {
        val code = "$malformed\n\ndef $followingDeclName(): Int32 = 1"
        val psiFile = parseWithoutCrashing(code)

        val declaration = PsiTreeUtil.findChildrenOfType(psiFile, FlixDefDecl::class.java)
            .firstOrNull { it.nameOrNull() == followingDeclName }
        assertNotNull(
            "Expected 'def $followingDeclName' to parse as a real declaration after recovering " +
                "from the preceding malformed one, but no FlixDefDecl with that name exists -- " +
                "the error element has swallowed the rest of the file",
            declaration
        )
        assertNull(
            "'def $followingDeclName' parsed, but inside an error element rather than as a " +
                "recovered top-level declaration",
            PsiTreeUtil.getParentOfType(declaration, PsiErrorElement::class.java)
        )
    }

    /**
     * The weaker guarantee, for inputs whose trailing incompleteness is *syntactically valid*.
     *
     * `def foo(): Int32 = 1 +` followed by another `def` parses with **no error at all**: Flix
     * allows a local `def` as an expression, so the following declaration becomes the right operand
     * of the `+`. The declaration is not lost -- it has PSI and is not inside an error element --
     * but it is nested rather than top-level.
     *
     * Upstream's `Parser2` differs here: `isRecoverInExpr` includes `isFirstInDecl`, so it breaks
     * out of an expression when it sees a declaration keyword. Reproducing that in a PEG grammar
     * would mean making `localDefExpr` decline a declaration-leading `def`, which the grammar has
     * no positional way to express. The divergence is recorded in
     * docs/intellij-flix-parser-evaluation.md rather than asserted away.
     */
    private fun assertFollowingDeclarationSurvives(malformed: String, followingDeclName: String) {
        val code = "$malformed\n\ndef $followingDeclName(): Int32 = 1"
        val psiFile = parseWithoutCrashing(code)

        val named = PsiTreeUtil.findChildrenOfType(psiFile, PsiElement::class.java)
            .firstOrNull { it.node?.elementType == FlixTypes.NAME_LOWERCASE && it.text == followingDeclName }
        assertNotNull(
            "'def $followingDeclName' vanished entirely after the preceding incomplete declaration",
            named
        )
        assertNull(
            "'def $followingDeclName' survived only inside an error element, meaning the rest of " +
                "the file was swallowed rather than parsed",
            PsiTreeUtil.getParentOfType(named, PsiErrorElement::class.java)
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
        assertFollowingDeclarationSurvives(
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
        assertFollowingDeclarationSurvives("def foo(): Int32 = 1 +", "afterDanglingOp")
    }

    fun testUnterminatedString() {
        assertHasParseError("""def foo(): String = "hello""")
    }

    fun testUnterminatedStringRecovers() {
        assertFollowingDeclarationSurvives("""def foo(): String = "hello""", "afterUnterminatedString")
    }

    fun testMissingDefBody() {
        assertHasParseError("def foo(): Int32 =")
    }

    fun testMissingDefBodyRecovers() {
        assertFollowingDeclarationSurvives("def foo(): Int32 =", "afterMissingBody")
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
