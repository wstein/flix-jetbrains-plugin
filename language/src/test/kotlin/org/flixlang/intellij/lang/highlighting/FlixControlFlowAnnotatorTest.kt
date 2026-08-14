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

    /** The span is the whole parenthesised condition, not the two glyphs around it. */
    fun testTheConditionIsOneSpan() {
        val roles = rolesIn("def f(): Int32 = if (x > 0) 1 else 2")

        assertEquals(
            listOf("FLIX_CONDITION" to "(x > 0)"),
            roles.filter { it.first == "FLIX_CONDITION" },
        )
    }

    /**
     * The parentheses of a *branch* are not the condition's.
     *
     * This is `Main.flix:88`, the line that prompted the feature: three parenthesised groups, of
     * which one is a condition. `findChildByType` looks only at direct children, which is what
     * keeps the other two out -- and a whole-subtree search would sweep them in.
     */
    fun testTheSpanStopsAtTheConditionAndDoesNotSwallowTheBranches() {
        val source = "def d(): Time = if (m2 > m1) (h2 - h1, m2 - m1) else ((h2 - h1) - 1, (60 + m2) - m1)"

        // The failure this guards against is not a missing style but the whole line emphasised:
        // taking the first `(` and the last `)` of the subtree would do exactly that.
        assertEquals(
            listOf("FLIX_CONDITION" to "(m2 > m1)"),
            rolesIn(source).filter { it.first == "FLIX_CONDITION" },
        )
    }

    /** `case p if e =>` -- a guard, and the grammar takes no parentheses here (`Flix.bnf:825`). */
    fun testAMatchGuardIsAGuard() {
        val roles = rolesIn("def f(): Int32 = match x { case y if y > 0 => 1 }")

        assertTrue("the guard was not distinguished: $roles", "FLIX_GUARD_KEYWORD" to "if" in roles)
        // No parentheses in this production, so the condition is the guard expression itself --
        // which is why the span is defined on the expression rather than on the parentheses.
        assertEquals(
            listOf("FLIX_CONDITION" to "y > 0"),
            roles.filter { it.first == "FLIX_CONDITION" },
        )
    }

    /** A match arm with no guard at all contributes no condition, and does not throw. */
    fun testAMatchArmWithoutAGuardHasNoCondition() {
        val roles = rolesIn("def f(): Int32 = match x { case y => 1 }")

        assertEquals(emptyList<Pair<String, String>>(), roles.filter { it.first == "FLIX_CONDITION" })
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
     * The default sets a font style and nothing else.
     *
     * This is the requirement that lets one rule serve both a single keyword and a span over a
     * whole condition. Any colour set here would be imposed on every token in the span.
     */
    fun testTheDefaultSetsOnlyAFontStyle() {
        val emphasis = FlixControlFlowAnnotator.EMPHASIS

        assertNull("a foreground would flatten every token in the condition", emphasis.foregroundColor)
        assertNull("a background would be a band, which is not what was asked for", emphasis.backgroundColor)
        assertNull(emphasis.effectColor)
        assertEquals(Font.BOLD or Font.ITALIC, emphasis.fontType)
    }

    /**
     * The platform contract this depends on, asserted directly.
     *
     * `TextAttributes.merge` composes layered highlighters field by field: a colour on the upper
     * layer overrides only when set, and font types are **or-ed**. That is the whole reason a
     * style-only overlay keeps each token's syntax colour and still adds weight and slant. If the
     * platform ever changed it, every condition in the editor would silently lose its colours --
     * so it is pinned here rather than trusted.
     */
    fun testAStyleOnlyOverlayKeepsTheColoursUnderneath() {
        val token = TextAttributes().apply {
            foregroundColor = JBColor.RED
            backgroundColor = JBColor.BLUE
            fontType = Font.PLAIN
        }

        val merged = TextAttributes.merge(token, FlixControlFlowAnnotator.EMPHASIS)

        assertEquals("the token lost its colour", JBColor.RED, merged.foregroundColor)
        assertEquals("the token lost its background", JBColor.BLUE, merged.backgroundColor)
        assertEquals("the emphasis was not added", Font.BOLD or Font.ITALIC, merged.fontType)
    }

    /** A style the scheme already asked for is kept, not replaced. */
    fun testAnExistingStyleIsAddedToRatherThanOverwritten() {
        val alreadyItalic = TextAttributes().apply { fontType = Font.ITALIC }

        val merged = TextAttributes.merge(alreadyItalic, FlixControlFlowAnnotator.EMPHASIS)

        assertEquals(Font.BOLD or Font.ITALIC, merged.fontType)
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
