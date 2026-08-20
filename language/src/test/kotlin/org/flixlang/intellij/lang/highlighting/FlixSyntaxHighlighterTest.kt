package org.flixlang.intellij.lang.highlighting

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Which colour key each token actually gets.
 *
 * Written because the highlighter had no tests, and had been assigning no key to anything: it
 * bucketed on `IElementType.toString()`, which for a Grammar-Kit token is its *display text* behind
 * a class prefix (`"FlixTokenType.def"`), not the constant's name. Every prefix rule missed, every
 * token fell through to the empty array, and LSP4IJ's semantic tokens painting the same buffer made
 * it look as though the highlighter worked.
 *
 * So these assertions deliberately run the real lexer over real Flix and read the key back. Nothing
 * short of that would have caught it.
 */
class FlixSyntaxHighlighterTest : BasePlatformTestCase() {

    /** Every key the highlighter assigns to [text], in token order, skipping unstyled tokens. */
    private fun keysOf(text: String): List<Pair<String, String>> {
        val highlighter = FlixSyntaxHighlighter()
        val lexer = highlighter.highlightingLexer
        lexer.start(text)
        val found = mutableListOf<Pair<String, String>>()
        while (lexer.tokenType != null) {
            val keys: Array<TextAttributesKey> = highlighter.getTokenHighlights(lexer.tokenType!!)
            if (keys.isNotEmpty()) {
                found += lexer.tokenText to keys.single().externalName
            }
            lexer.advance()
        }
        return found
    }

    private fun keyOf(text: String, token: String): String? =
        keysOf(text).firstOrNull { it.first == token }?.second

    fun testKeywordsAreColoured() {
        val source = "def f(): Unit = ()\nenum E { case A }\ntype alias T = String"

        assertEquals("FLIX_KEYWORD", keyOf(source, "def"))
        assertEquals("FLIX_KEYWORD", keyOf(source, "enum"))
        assertEquals("FLIX_KEYWORD", keyOf(source, "type"))
    }

    fun testCommentsStringsNumbersAndAnnotationsAreColoured() {
        val source = "// note\n@Test\ndef f(): String = \"text\"\ndef g(): Int32 = 42"

        assertEquals("FLIX_COMMENT", keyOf(source, "// note"))
        assertEquals("FLIX_STRING", keyOf(source, "\"text\""))
        assertEquals("FLIX_NUMBER", keyOf(source, "42"))
        assertEquals("FLIX_ANNOTATION", keyOf(source, "@Test"))
    }

    /**
     * The whole file is not silently unstyled.
     *
     * The defect this replaces did not fail one bucket; it failed all of them at once, and a
     * per-token assertion could in principle pass while most of a file stayed blank. This is the
     * shape of the original bug, asserted directly.
     */
    fun testARealisticFileGetsColour() {
        val source = """
            /// A module.
            mod M {
                // The test flag, for now.
                @Test
                def f(x: Int32): String = if (x <= 9) "0" else "${'$'}{x}"
            }
        """.trimIndent()

        val keys = keysOf(source)
        assertTrue("nothing in the file was coloured", keys.isNotEmpty())
        assertTrue("keywords went uncoloured: $keys", keys.any { it.second == "FLIX_KEYWORD" })
        assertTrue("comments went uncoloured: $keys", keys.any { it.second == "FLIX_COMMENT" })
        assertTrue("doc comments went uncoloured: $keys", keys.any { it.second == "FLIX_DOC_COMMENT" })
        assertTrue("strings went uncoloured: $keys", keys.any { it.second == "FLIX_STRING" })
    }

    /**
     * `///` documents the declaration below it; `//` remarks on a line. They are different things
     * and every other language in the IDE colours them differently, so these do too.
     */
    fun testDocCommentsAreNotOrdinaryComments() {
        val source = "/// Adds one.\n// a remark\ndef inc(x: Int32): Int32 = x + 1"

        assertEquals("FLIX_DOC_COMMENT", keyOf(source, "/// Adds one."))
        assertEquals("FLIX_COMMENT", keyOf(source, "// a remark"))
    }

    /**
     * Four slashes are *not* documentation, which is the compiler's rule and not a guess made here.
     *
     * `Lexer.acceptLineOrDocComment` says it outright -- "a doc comment leads with exactly 3
     * slashes, for example `//// example` is NOT a doc comment" -- and adding a slash is a common
     * way to comment out a doc line. Colouring that as documentation would say the opposite of what
     * the compiler does with it.
     */
    fun testFourSlashesAreAnOrdinaryComment() {
        assertEquals("FLIX_COMMENT", keyOf("//// heading\ndef f(): Unit = ()", "//// heading"))
    }

    /** Punctuation carries no key, which is what leaves it the scheme's default text colour. */
    fun testPunctuationIsLeftAlone() {
        assertNull(keyOf("def f(): Unit = ()", "("))
        assertNull(keyOf("def f(): Unit = ()", "="))
    }

    fun testControlFlowKeywordsGetTheirOwnKey() {
        val source = "def f(x: Int32): String = if (x <= 9) \"small\" else \"large\""

        assertEquals("FLIX_CONTROL_FLOW_KEYWORD", keyOf(source, "if"))
        assertEquals("FLIX_CONTROL_FLOW_KEYWORD", keyOf(source, "else"))
    }

    /** A keyword that declares rather than branches stays an ordinary keyword. */
    fun testDeclarationKeywordsAreNotControlFlow() {
        val source = "def f(): Unit = ()\nenum E { case A }\ntype alias T = String"

        assertEquals("FLIX_KEYWORD", keyOf(source, "def"))
        assertEquals("FLIX_KEYWORD", keyOf(source, "enum"))
    }

    /**
     * `case` is not control flow, because it is not only a match arm.
     *
     * It introduces an `enum` member too (`Flix.bnf:449`), and the lexer cannot tell that from a
     * `match` arm (`:825`). Colouring it here would light up every enum declaration in the file as
     * control flow -- confidently, and wrongly.
     */
    fun testCaseIsLeftToThePsi() {
        assertEquals("FLIX_KEYWORD", keyOf("enum E { case A, case B }", "case"))
        assertEquals("FLIX_KEYWORD", keyOf("match x { case y => y }", "case"))
    }

    /**
     * All four `if`s are coloured alike, and that is the intended limit of a lexer.
     *
     * `IF_KW` is a conditional expression, a match guard, a comprehension guard and a Datalog
     * constraint (`Flix.bnf:790`, `:825`, `:845`, `:1024`). They are all conditional, so one key
     * for the four is right here; telling them apart needs the PSI.
     */
    fun testEveryIfIsColouredTheSame() {
        val conditional = keyOf("def f(): Int32 = if (a) 1 else 2", "if")
        val matchGuard = keyOf("def f(): Int32 = match x { case y if y > 0 => 1 }", "if")
        val datalogGuard = keyOf("def f(): Unit = #{ P(x) :- Q(x), if (x > 0). }", "if")

        assertEquals("FLIX_CONTROL_FLOW_KEYWORD", conditional)
        assertEquals(conditional, matchGuard)
        assertEquals(conditional, datalogGuard)
    }

    /**
     * The control-flow key inherits the keyword key, which is what makes it off by default.
     *
     * If it ever gains attributes of its own, every user's `if` changes colour without anyone
     * choosing that. The fallback is the design, so it is asserted rather than assumed.
     */
    fun testControlFlowInheritsKeywordSoItIsInvisibleUntilChosen() {
        assertEquals(
            FlixSyntaxHighlighter.KEYWORD,
            FlixSyntaxHighlighter.CONTROL_FLOW_KEYWORD.fallbackAttributeKey,
        )
    }
}
