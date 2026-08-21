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
     * A key with no descriptor is unreachable: it still renders, via its fallback or via the
     * annotator's own default, and there is nowhere to change it. Comparing against the highlighter's own set rather than a copied list
     * means adding a key to one and not the other fails here.
     */
    fun testEveryHighlighterKeyIsOffered() {
        val offered = page.attributeDescriptors.map { it.key }.toSet()
        val defined = setOf(
            FlixSyntaxHighlighter.KEYWORD,
            FlixSyntaxHighlighter.CONTROL_FLOW_KEYWORD,
            FlixSyntaxHighlighter.GUARD_KEYWORD,
            FlixSyntaxHighlighter.DATALOG_GUARD_KEYWORD,
            FlixSyntaxHighlighter.CONDITION,
            FlixSyntaxHighlighter.STRING,
            FlixSyntaxHighlighter.NUMBER,
            FlixSyntaxHighlighter.COMMENT,
            FlixSyntaxHighlighter.DOC_COMMENT,
            FlixSyntaxHighlighter.ANNOTATION,
            FlixSyntaxHighlighter.BAD_CHARACTER,
            FlixSyntaxHighlighter.FUNCTION_NAME,
            FlixSyntaxHighlighter.PARAMETER,
            FlixSyntaxHighlighter.TYPE_PARAMETER,
            FlixSyntaxHighlighter.LOCAL_VARIABLE,
            FlixSyntaxHighlighter.TYPE_NAME,
            FlixSyntaxHighlighter.ENUM_CASE,
            FlixSyntaxHighlighter.FIELD_NAME,
            FlixSyntaxHighlighter.TRAIT_NAME,
            FlixSyntaxHighlighter.EFFECT_NAME,
        )

        assertEquals(defined, offered)
    }

    /**
     * Every tag the demo uses is declared, and every declared tag is used.
     *
     * A tag with no entry in the map is not ignored: the platform fails while rendering the page,
     * so the whole settings panel breaks rather than one word losing its colour. The reverse -- a
     * declared tag the demo never uses -- is a key the preview silently stops demonstrating.
     */
    fun testTheDemoTagsAndTheDescriptorMapAgree() {
        val used = Regex("<([A-Za-z]+)>").findAll(page.demoText).map { it.groupValues[1] }.toSet()
        val declared = page.getAdditionalHighlightingTagToDescriptorMap().orEmpty().keys

        assertEquals(declared, used)
    }

    /** No two rows may share a display name, or one of them cannot be identified in the list. */
    fun testDescriptorNamesAreDistinct() {
        val names = page.attributeDescriptors.map { it.displayName }

        assertEquals(names.size, names.toSet().size)
    }

    /**
     * Every role the fallback annotator assigns appears in the demo, tagged.
     *
     * The preview is lexed rather than parsed, so a role that exists only in the PSI shows up there
     * only if the demo marks it. A key that is offered in the list but never demonstrated leaves a
     * reader choosing a colour for something they cannot see.
     */
    fun testTheDemoShowsEveryRoleTheParserAssigns() {
        val declared = page.getAdditionalHighlightingTagToDescriptorMap().orEmpty()
        val used = Regex("<([A-Za-z]+)>").findAll(page.demoText).map { it.groupValues[1] }.toSet()

        listOf("fn", "param", "tparam", "local", "type", "case", "field", "trait", "eff").forEach { tag ->
            assertTrue("the demo never shows <$tag>", tag in used)
            assertTrue("<$tag> is used but not declared", tag in declared)
        }
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

        assertTrue("no conditional expression", demo.contains("if <condition>(t <= 9)</condition>"))
        assertTrue("no match guard", demo.contains("<guard>if</guard> <condition>wait > 30</condition>"))
        assertTrue("no Datalog constraint", demo.contains("<datalogGuard>if</datalogGuard>"))
        assertTrue("no comprehension guard", demo.contains("foreach (<local>leg</local> <- legs)"))
        assertTrue(
            "no enum case, which is the counter-example",
            demo.contains("case <case>Direct</case>(<type>Station</type>, <type>Station</type>)"),
        )
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
        // The platform strips the markup before lexing; do the same, or the tags themselves
        // would be lexed as Flix and the assertion would be about nonsense.
        lexer.start(page.demoText.replace(Regex("</?[A-Za-z]+>"), ""))
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
