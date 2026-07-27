package org.flixlang.intellij.lang.editor

import com.intellij.testFramework.ParsingTestCase
import org.flixlang.intellij.lang.FlixParserDefinition
import org.flixlang.intellij.lang.psi.FlixTypes

/**
 * Behaviour of the editor-basics implementations, exercised directly.
 *
 * They are instantiated rather than looked up through `LanguageBraceMatching.forLanguage` and
 * friends, because an extension lookup needs the assembled plugin's descriptor and a
 * content-module descriptor is inert outside it. `FlixPluginDescriptorTest` covers the
 * registration; this covers what the classes actually do. Each failure then names one cause
 * instead of two.
 */
class FlixEditorBasicsTest : ParsingTestCase("", "flix", FlixParserDefinition()) {

    override fun getTestDataPath(): String = ""

    override fun skipSpaces(): Boolean = false

    fun testBraceMatcherPairsAreCorrect() {
        val pairs = FlixBraceMatcher().pairs.associateBy { it.leftBraceType }
        assertEquals(FlixTypes.CURLY_R, pairs.getValue(FlixTypes.CURLY_L).rightBraceType)
        assertTrue(
            "Curly braces must be structural, or the platform will not treat them as block " +
                "delimiters for indentation and navigation",
            pairs.getValue(FlixTypes.CURLY_L).isStructural,
        )
        assertEquals(FlixTypes.PAREN_R, pairs.getValue(FlixTypes.PAREN_L).rightBraceType)
        assertEquals(FlixTypes.BRACKET_R, pairs.getValue(FlixTypes.BRACKET_L).rightBraceType)

        // The fixpoint/schema forms `#( ... )` and `#{ ... }` open with their own token but close
        // with an ordinary `)`/`}`, while the extensible-row type `#| ... |#` is its own pair.
        assertEquals(FlixTypes.PAREN_R, pairs.getValue(FlixTypes.HASH_PAREN_L).rightBraceType)
        assertEquals(FlixTypes.CURLY_R, pairs.getValue(FlixTypes.HASH_CURLY_L).rightBraceType)
        assertEquals(FlixTypes.BAR_HASH, pairs.getValue(FlixTypes.HASH_BAR).rightBraceType)
    }

    fun testCommenterPrefixes() {
        val commenter = FlixCommenter()
        assertEquals("//", commenter.lineCommentPrefix)
        assertEquals("/*", commenter.blockCommentPrefix)
        assertEquals("*/", commenter.blockCommentSuffix)
    }

    fun testStringLiteralTokensAreQuoteHandlerOpeners() {
        // The quote handler decides whether a `"` starts a literal, which is what stops the editor
        // auto-closing a quote that is already closed. It reads the token type at the offset, so
        // the property worth asserting here is that the lexer produces a string-literal token
        // where one is expected.
        val file = createPsiFile("Test", """def foo(): String = "hi"""")
        ensureParsed(file)
        val quote = file.text.indexOf('"')
        val leaf = file.findElementAt(quote)
        assertNotNull("No PSI leaf at the opening quote", leaf)
        assertEquals(FlixTypes.LITERAL_STRING, leaf!!.node.elementType)
    }
}
