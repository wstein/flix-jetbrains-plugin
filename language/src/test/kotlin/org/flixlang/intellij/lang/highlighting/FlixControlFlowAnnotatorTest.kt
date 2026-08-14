package org.flixlang.intellij.lang.highlighting

import com.intellij.lang.LanguageParserDefinitions
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBColor
import org.flixlang.intellij.lang.FlixFileType
import org.flixlang.intellij.lang.FlixLanguage
import org.flixlang.intellij.lang.FlixParserDefinition
import java.awt.Font

/**
 * That the four `if`s are told apart, and that half-written code is not.
 *
 * Driven through the real parser rather than by constructing PSI: the whole claim is that the
 * parser already knows which production an `if` belongs to, so a test that asserted against
 * hand-built nodes would be asserting about its own fixture.
 */
class FlixControlFlowAnnotatorTest : BasePlatformTestCase() {

    /**
     * The parser has to be registered by hand.
     *
     * A module-local fixture never reads the content-module descriptor, so `<lang.parserDefinition>`
     * does not reach [LanguageParserDefinitions] and a `.flix` document comes back as plain text
     * with no PSI at all. Every assertion below would then find no roles and pass or fail for a
     * reason that has nothing to do with the annotator -- which is exactly how the first run of
     * this suite behaved.
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

    /** Every role the annotator contributes to [source], as `key -> the text it covers`. */
    private fun rolesIn(source: String): List<Pair<String, String>> {
        val file = myFixture.configureByText(FlixFileType.INSTANCE, source)
        val annotator = FlixControlFlowAnnotator()
        val found = mutableListOf<Pair<String, String>>()
        file.accept(
            object : PsiRecursiveElementVisitor() {
                override fun visitElement(element: PsiElement) {
                    annotator.rolesOf(element).forEach {
                        found += it.key.externalName to it.range.substring(file.text)
                    }
                    super.visitElement(element)
                }
            },
        )
        return found
    }

    private fun keysFor(source: String, text: String): List<String> =
        rolesIn(source).filter { it.second == text }.map { it.first }

    fun testAConditionalIsControlFlow() {
        val roles = rolesIn("def f(): Int32 = if (x > 0) 1 else 2")

        assertTrue("the `if` was not marked control flow: $roles", "FLIX_CONTROL_FLOW_KEYWORD" to "if" in roles)
        assertTrue("the `else` was not marked control flow: $roles", "FLIX_CONTROL_FLOW_KEYWORD" to "else" in roles)
    }

    fun testTheConditionParenthesesAreTheirOwnRole() {
        val roles = rolesIn("def f(): Int32 = if (x > 0) 1 else 2")

        assertEquals(
            "exactly the two condition parentheses, and no others",
            2,
            roles.count { it.first == "FLIX_CONDITION_PARENTHESES" },
        )
    }

    /**
     * The parentheses of a *branch* are not the condition's.
     *
     * This is `Main.flix:88`, the line that prompted the feature: three parenthesised groups, of
     * which one is a condition. `findChildByType` looks only at direct children, which is what
     * keeps the other two out -- and a whole-subtree search would sweep them in.
     */
    fun testBranchParenthesesAreLeftAlone() {
        val source = "def d(): Time = if (m2 > m1) (h2 - h1, m2 - m1) else ((h2 - h1) - 1, (60 + m2) - m1)"

        assertEquals(2, rolesIn(source).count { it.first == "FLIX_CONDITION_PARENTHESES" })
    }

    /** `case p if e =>` -- a guard, and the grammar takes no parentheses here (`Flix.bnf:825`). */
    fun testAMatchGuardIsAGuard() {
        val roles = rolesIn("def f(): Int32 = match x { case y if y > 0 => 1 }")

        assertTrue("the guard was not distinguished: $roles", "FLIX_GUARD_KEYWORD" to "if" in roles)
        assertEquals("a match guard has no parentheses to colour", 0, roles.count { it.first == "FLIX_CONDITION_PARENTHESES" })
    }

    /**
     * The Datalog constraint is the one that is not a conditional.
     *
     * Spelled identically to one -- keyword, parentheses, boolean expression -- and it filters a
     * rule's solutions rather than choosing a branch. This is the distinction the whole annotator
     * exists for, so it is the assertion that matters most.
     */
    fun testADatalogConstraintIsNotAConditional() {
        val source = "def f(): Unit = #{ Path(x, z) :- Path(x, y), Edge(y, z), if (x != z). }"

        val keys = keysFor(source, "if")
        assertTrue("the Datalog `if` was not distinguished: ${rolesIn(source)}", "FLIX_DATALOG_GUARD_KEYWORD" in keys)
        assertFalse("a Datalog constraint must not read as a conditional", "FLIX_CONTROL_FLOW_KEYWORD" in keys)
    }

    /** The two `if`s of one file get different keys, which is the point stated in one assertion. */
    fun testTheSameTokenGetsDifferentRolesInOneFile() {
        val source = """
            def f(): Int32 = if (a) 1 else 2
            def g(): Unit = #{ P(x) :- Q(x), if (x > 0). }
        """.trimIndent()

        val keys = keysFor(source, "if").toSet()

        assertEquals(setOf("FLIX_CONTROL_FLOW_KEYWORD", "FLIX_DATALOG_GUARD_KEYWORD"), keys)
    }

    /**
     * Half-written code contributes nothing, and does not throw.
     *
     * An annotator runs on every keystroke, so this is its normal input rather than an edge case.
     * The behaviour is inherited rather than coded: the parser does not build a partial `ifExpr`
     * for `if (`, so there is no node to match and nothing to colour. Asserted because the
     * alternative -- recovering an incomplete `ifExpr` and colouring a range that is about to move
     * -- is a plausible future change to the grammar, and this is where it would show up.
     */
    fun testIncompleteCodeContributesNothing() {
        assertEquals(emptyList<Pair<String, String>>(), rolesIn("def f(): Int32 = if ("))
        assertEquals(emptyList<Pair<String, String>>(), rolesIn("def f(): Int32 = if"))
        assertEquals(emptyList<Pair<String, String>>(), rolesIn("def f(): Int32 = "))
    }

    /**
     * The default emphasis adds weight and takes nothing away.
     *
     * This is why the bold is applied here rather than through `additionalTextAttributes`: a scheme
     * entry replaces a key's attributes, so the guard would keep the bold and lose the inherited
     * keyword colour. Deriving from the resolved attributes keeps every colour the scheme chose.
     */
    fun testTheDefaultEmphasisKeepsTheInheritedColours() {
        val base = TextAttributes().apply {
            foregroundColor = JBColor.RED
            backgroundColor = JBColor.BLUE
            fontType = Font.ITALIC
        }

        val bold = FlixControlFlowAnnotator.emphasised(base)

        assertEquals(JBColor.RED, bold.foregroundColor)
        assertEquals(JBColor.BLUE, bold.backgroundColor)
        assertTrue("the italic the scheme asked for was dropped", bold.fontType and Font.ITALIC != 0)
        assertTrue("no emphasis was added", bold.fontType and Font.BOLD != 0)
        assertEquals("the original must not be mutated", Font.ITALIC, base.fontType)
    }

    /**
     * A complete conditional is still annotated while the next declaration is being typed.
     *
     * The normal state of a file in an editor. The half-written `def` comes after the complete one
     * because that is the direction people type in, and because what happens *before* an error is
     * the parser's recovery behaviour rather than this annotator's -- `FlixErrorRecoveryTest` owns
     * that question.
     */
    fun testAnUnfinishedDeclarationBelowDoesNotSuppressTheOneAbove() {
        val source = """
            def f(): Int32 = if (a) 1 else 2
            def half
        """.trimIndent()

        assertTrue(
            "a complete conditional was skipped because of an error later in the file",
            "FLIX_CONTROL_FLOW_KEYWORD" to "if" in rolesIn(source),
        )
    }
}
