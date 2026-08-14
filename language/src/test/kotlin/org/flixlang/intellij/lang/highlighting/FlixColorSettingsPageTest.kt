package org.flixlang.intellij.lang.highlighting

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The page is the only way a user reaches any of the plugin's colour keys, so what it omits is
 * invisible rather than merely missing.
 */
class FlixColorSettingsPageTest : BasePlatformTestCase() {

    private val page = FlixColorSettingsPage()

    /**
     * Every key the highlighter can assign is offered.
     *
     * A key with no descriptor is unreachable: it has a fallback, so it renders, and there is
     * nowhere to change it. Comparing against the highlighter's own set rather than a copied list
     * means adding a key to one and not the other fails here.
     */
    fun testEveryHighlighterKeyIsOffered() {
        val offered = page.attributeDescriptors.map { it.key }.toSet()
        val defined = setOf(
            FlixSyntaxHighlighter.KEYWORD,
            FlixSyntaxHighlighter.CONTROL_FLOW_KEYWORD,
            FlixSyntaxHighlighter.STRING,
            FlixSyntaxHighlighter.NUMBER,
            FlixSyntaxHighlighter.COMMENT,
            FlixSyntaxHighlighter.ANNOTATION,
            FlixSyntaxHighlighter.BAD_CHARACTER,
        )

        assertEquals(defined, offered)
    }

    /** No two rows may share a display name, or one of them cannot be identified in the list. */
    fun testDescriptorNamesAreDistinct() {
        val names = page.attributeDescriptors.map { it.displayName }

        assertEquals(names.size, names.toSet().size)
    }

    /**
     * The demo carries all four grammatical roles of `if`, plus the `case` that is not control flow.
     *
     * That is the page's whole argument: `IF_KW` appears in four separate productions
     * (`Flix.bnf:790`, `:825`, `:845`, `:1024`) and one of them is not a conditional at all. A
     * preview that showed only the conditional would teach the reader the opposite.
     */
    fun testTheDemoShowsEveryRoleTheKeyCovers() {
        val demo = page.demoText

        assertTrue("no conditional expression", demo.contains("if (t <= 9)"))
        assertTrue("no match guard", demo.contains("if wait > 30"))
        assertTrue("no Datalog constraint", demo.contains(":- Path(x, y), Edge(y, z), if (x != z)"))
        assertTrue("no comprehension guard", demo.contains("foreach (leg <- legs)"))
        assertTrue("no enum case, which is the counter-example", demo.contains("case Direct(Station, Station)"))
    }

    /**
     * The demo actually lexes into the keys it is meant to demonstrate.
     *
     * A preview is only a preview of something if the highlighter colours it, and this one is
     * hand-written rather than taken from a file that the parser tests already cover.
     */
    fun testTheDemoTextIsColoured() {
        val highlighter = page.highlighter
        val lexer = highlighter.highlightingLexer
        lexer.start(page.demoText)
        val keys = mutableSetOf<String>()
        while (lexer.tokenType != null) {
            highlighter.getTokenHighlights(lexer.tokenType!!).forEach { keys += it.externalName }
            lexer.advance()
        }

        assertTrue("the demo shows no control flow: $keys", "FLIX_CONTROL_FLOW_KEYWORD" in keys)
        assertTrue("the demo shows no plain keyword: $keys", "FLIX_KEYWORD" in keys)
        assertTrue("the demo shows no comment: $keys", "FLIX_COMMENT" in keys)
        assertTrue("the demo shows no string: $keys", "FLIX_STRING" in keys)
    }
}
