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
                @Test
                def f(x: Int32): String = if (x <= 9) "0" else "${'$'}{x}"
            }
        """.trimIndent()

        val keys = keysOf(source)
        assertTrue("nothing in the file was coloured", keys.isNotEmpty())
        assertTrue("keywords went uncoloured: $keys", keys.any { it.second == "FLIX_KEYWORD" })
        assertTrue("comments went uncoloured: $keys", keys.any { it.second == "FLIX_COMMENT" })
        assertTrue("strings went uncoloured: $keys", keys.any { it.second == "FLIX_STRING" })
    }

    /** Punctuation carries no key, which is what leaves it the scheme's default text colour. */
    fun testPunctuationIsLeftAlone() {
        assertNull(keyOf("def f(): Unit = ()", "("))
        assertNull(keyOf("def f(): Unit = ()", "="))
    }
}
