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
 * Malformed / mid-edit-shaped variants of the same JVM-interop, extensible-variant, and
 * fixpoint/provenance forms covered happy-path in [FlixRareSyntaxTest]. A user typing one of
 * these rarer constructs is just as likely to be mid-edit as with any common one, so recovery
 * quality matters here too, not just "does it parse when finished."
 */
class FlixRareSyntaxRecoveryTest : ParsingTestCase("", "flix", FlixParserDefinition()) {
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
     * Asserts the following declaration survives as a real top-level [FlixDefDecl], outside any
     * error element.
     *
     * An earlier version looked only for a NAME_LOWERCASE leaf, which proves nothing: the lexer
     * emits tokens regardless, so the identifier is still there inside a PsiErrorElement that has
     * swallowed the whole rest of the file -- exactly the failure `recoverWhile` prevents. See
     * FlixErrorRecoveryTest for the weaker guarantee that applies when the incomplete input is
     * syntactically valid.
     */
    /**
     * The weaker guarantee, for inputs whose incompleteness sits inside an expression.
     *
     * Flix allows a local `def` as an expression, so an unfinished expression can legitimately
     * absorb the following declaration instead of failing -- often with no error at all. What still
     * has to hold is that the declaration is not *lost*: it has PSI and is not buried in an error
     * element that swallowed the rest of the file. See FlixErrorRecoveryTest for the divergence
     * from upstream `Parser2` this reflects.
     */
    private fun assertFollowingDeclarationSurvives(malformed: String, followingDeclName: String) {
        val code = "$malformed\n\ndef $followingDeclName(): Int32 = 1"
        val psiFile = parseWithoutCrashing(code)

        val named = PsiTreeUtil.findChildrenOfType(psiFile, PsiElement::class.java)
            .firstOrNull { it.node?.elementType == FlixTypes.NAME_LOWERCASE && it.text == followingDeclName }
        assertNotNull(
            "'def $followingDeclName' vanished entirely after the preceding incomplete construct",
            named
        )
        assertNull(
            "'def $followingDeclName' survived only inside an error element, meaning the rest of " +
                "the file was swallowed rather than parsed",
            PsiTreeUtil.getParentOfType(named, PsiErrorElement::class.java)
        )
    }

    private fun assertRecoversAndContinues(malformed: String, followingDeclName: String) {
        val code = "$malformed\n\ndef $followingDeclName(): Int32 = 1"
        val psiFile = parseWithoutCrashing(code)

        val declaration = PsiTreeUtil.findChildrenOfType(psiFile, FlixDefDecl::class.java)
            .firstOrNull { it.nameOrNull() == followingDeclName }
        assertNotNull(
            "Expected 'def $followingDeclName' to parse as a real declaration after recovering " +
                "from the preceding malformed one",
            declaration
        )
        assertNull(
            "'def $followingDeclName' parsed inside an error element rather than as a recovered " +
                "top-level declaration",
            PsiTreeUtil.getParentOfType(declaration, PsiErrorElement::class.java)
        )
    }


    // ---------------------------------------------------------------------------------------
    // JVM interop
    // ---------------------------------------------------------------------------------------

    fun testUnclosedConstructorCall() {
        assertHasParseError(
            """
            import java.math.BigDecimal
            import java.math.BigInteger

            def foo(): BigDecimal =
                new BigDecimal(new BigInteger("12345"), 2
            """.trimIndent()
        )
    }

    fun testUnclosedConstructorCallRecovers() {
        assertFollowingDeclarationSurvives(
            """
            import java.math.BigDecimal
            import java.math.BigInteger

            def foo(): BigDecimal =
                new BigDecimal(new BigInteger("12345"), 2
            """.trimIndent(),
            "afterUnclosedCtor"
        )
    }

    fun testUnclosedAnonymousClassBody() {
        assertHasParseError(
            """
            import java.io.Serializable

            def foo(): Serializable \ IO =
                new Serializable {
            """.trimIndent()
        )
    }

    fun testUnclosedAnonymousClassBodyRecovers() {
        assertFollowingDeclarationSurvives(
            """
            import java.io.Serializable

            def foo(): Serializable \ IO =
                new Serializable {
            """.trimIndent(),
            "afterUnclosedAnonClass"
        )
    }

    fun testAnonymousClassMethodMissingBody() {
        assertHasParseError(
            """
            import java.util.Comparator

            def foo(): Unit =
                let anon = new Comparator[String] {
                    def compare(_this: Comparator[String], _t: String, _u: String): Int32 =
                };
            """.trimIndent()
        )
    }

    fun testStructAllocationMissingFieldValue() {
        assertHasParseError(
            """
            struct Point[r] {
                mut x: Int32,
                mut y: Int32
            }

            def makePoint(rc: Region[r]): Point[r] \ r =
                new Point @ rc { x = 1, y =
            """.trimIndent()
        )
    }

    // ---------------------------------------------------------------------------------------
    // Extensible variants
    // ---------------------------------------------------------------------------------------

    fun testUnclosedExtTagArgs() {
        assertHasParseError("def foo(): #| A(Int32) |# = xvar A(")
    }

    fun testUnclosedExtTagArgsRecovers() {
        assertRecoversAndContinues("def foo(): #| A(Int32) |# = xvar A(", "afterUnclosedXvar")
    }

    fun testUnclosedExtRowType() {
        assertHasParseError("def foo(): #| A(Int32) = xvar A(1)")
    }

    fun testExtMatchMissingArrow() {
        assertHasParseError(
            """
            def foo(): Unit =
                ematch xvar A(1) {
                    case A(x)
                }
            """.trimIndent()
        )
    }

    fun testExtMatchIncompleteRuleBody() {
        assertHasParseError(
            """
            def foo(): Unit =
                ematch xvar A(1) {
                    case A(x) =>
                }
            """.trimIndent()
        )
    }

    fun testExtMatchIncompleteRuleBodyRecovers() {
        assertFollowingDeclarationSurvives(
            """
            def foo(): Unit =
                ematch xvar A(1) {
                    case A(x) =>
                }
            """.trimIndent(),
            "afterIncompleteExtMatch"
        )
    }

    // ---------------------------------------------------------------------------------------
    // Fixpoint / Datalog / provenance
    // ---------------------------------------------------------------------------------------

    fun testUnclosedConstraintSet() {
        assertHasParseError(
            """
            def foo(): Unit =
                let p = #{
                    A(1).
            """.trimIndent()
        )
    }

    fun testUnclosedConstraintSetRecovers() {
        assertFollowingDeclarationSurvives(
            """
            def foo(): Unit =
                let p = #{
                    A(1).
            """.trimIndent(),
            "afterUnclosedConstraintSet"
        )
    }

    fun testConstraintMissingDotTerminator() {
        assertHasParseError(
            """
            def foo(): Unit =
                let p = #{
                    A(1) B(2).
                };
            """.trimIndent()
        )
    }

    fun testSolveProjectMissingPredicateName() {
        assertHasParseError(
            """
            def foo(): Unit =
                let q = solve p project
            """.trimIndent()
        )
    }

    fun testQueryMissingSelectBody() {
        assertHasParseError(
            """
            def foo(): Unit =
                let r = query p select
            """.trimIndent()
        )
    }

    fun testQueryMissingSelectBodyRecovers() {
        assertFollowingDeclarationSurvives(
            """
            def foo(): Unit =
                let r = query p select
            """.trimIndent(),
            "afterIncompleteQuery"
        )
    }

    fun testPQueryUnclosedWithSet() {
        assertHasParseError(
            """
            def foo(): Unit =
                let result = pquery pm select R(1) with {
            """.trimIndent()
        )
    }

    fun testPQueryUnclosedWithSetRecovers() {
        assertFollowingDeclarationSurvives(
            """
            def foo(): Unit =
                let result = pquery pm select R(1) with {
            """.trimIndent(),
            "afterUnclosedPQuery"
        )
    }

    fun testInjectMissingArity() {
        assertHasParseError(
            """
            def foo(): Unit =
                let pr = inject l into A/
            """.trimIndent()
        )
    }
}
