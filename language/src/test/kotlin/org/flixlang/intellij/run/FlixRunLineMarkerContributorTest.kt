package org.flixlang.intellij.run

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase
import org.flixlang.intellij.lang.FlixParserDefinition

/**
 * The gutter run arrow next to `def main` -- the gap that motivated adopting this language layer
 * in the first place.
 *
 * [FlixRunLineMarkerContributor] is invoked directly on parsed PSI rather than through
 * `myFixture.findAllGutters()`, because driving the real gutter needs the assembled plugin's
 * descriptor and a content-module descriptor is inert outside it. `FlixPluginDescriptorTest`
 * asserts the registration; this asserts the decision the contributor makes.
 *
 * The platform calls `getInfo` once per leaf element in the file, so the interesting property is
 * not merely "main produces an icon" but "exactly one leaf does" -- otherwise the icon lands on
 * every token of the declaration, or on the wrong line.
 */
class FlixRunLineMarkerContributorTest : ParsingTestCase("", "flix", FlixParserDefinition()) {

    override fun getTestDataPath(): String = ""

    override fun skipSpaces(): Boolean = false

    private fun markedLeaves(code: String): List<PsiElement> {
        val file = createPsiFile("Test", code)
        ensureParsed(file)
        return PsiTreeUtil.collectElements(file) { it.firstChild == null }
            .filter { FlixRunLineMarkerContributor.isRunnableEntryPointAnchor(it) }
    }

    fun testExactlyOneLeafIsMarkedForMain() {
        val marked = markedLeaves(
            """
            def helper(): Int32 = 42

            def main(): Int32 = helper()
            """.trimIndent(),
        )
        assertEquals("Exactly one leaf should carry the run marker, got $marked", 1, marked.size)
        assertEquals("main", marked.single().text)
    }

    fun testMarkerAnchorsOnTheNameNotTheKeyword() {
        // Anchoring on `def`, the colon, or the body would put the icon on a plausible-looking but
        // wrong line for any declaration that spans more than one.
        val marked = markedLeaves(
            """
            def main(
            ): Int32 =
                42
            """.trimIndent(),
        ).single()
        assertEquals("main", marked.text)
    }

    fun testNoMarkerWithoutMain() {
        assertTrue(markedLeaves("def helper(): Int32 = 42").isEmpty())
    }

    fun testNoMarkerOnIdentifiersThatMerelyMentionMain() {
        // `main` appearing as a call, a parameter name or a substring of another name must not
        // attract an icon -- only a declaration named exactly `main`.
        val marked = markedLeaves(
            """
            def mainHelper(main: Int32): Int32 = main

            def other(): Int32 = mainHelper(1)
            """.trimIndent(),
        )
        assertTrue("No declaration is named `main`, got $marked", marked.isEmpty())
    }
}
