package org.flixlang.intellij.lang

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
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
        assertRecoversAndContinues(
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
        assertRecoversAndContinues(
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
        assertRecoversAndContinues(
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
        assertRecoversAndContinues(
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
        assertRecoversAndContinues(
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
        assertRecoversAndContinues(
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
