package org.flixlang.intellij.editor

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase
import org.flixlang.intellij.lang.FlixParserDefinition
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every live template expands to Flix that parses.
 *
 * A template ships syntax with authority: what it expands to is what a user learns the language
 * looks like, and an editor offering it implies it is correct. Reviewing them catches typos and
 * nothing else -- Flix is still moving, and a construct whose syntax changed would keep being
 * offered in its old shape indefinitely.
 *
 * This repository does not have to review them. It owns a parser measured at 427 of 427 files in
 * the upstream corpus, so a template can be expanded and *parsed*, and a wrong one fails the build
 * rather than reaching an editor.
 *
 * ## Why each template declares where it goes
 *
 * A template is a fragment. `def` parses at the top level; `region` only inside a function body.
 * Parsing every one bare would fail the expression templates, and parsing every one wrapped would
 * fail the declarations -- so [SURROUNDINGS] says which, and a template missing an entry fails
 * rather than being skipped. That map is a second list of template names, which is exactly the kind
 * of duplication that drifts, so [testEverySurroundingIsUsedAndEveryTemplateHasOne] holds the two
 * against each other.
 */
class FlixLiveTemplateSyntaxTest : ParsingTestCase("", "flix", FlixParserDefinition()) {

    override fun getTestDataPath(): String = ""

    override fun skipSpaces(): Boolean = true

    fun testEveryTemplateExpandsToFlixThatParses() {
        templates().forEach { template ->
            val abbreviation = template.getAttribute("name")
            val expansion = expand(template)
            val source = SURROUNDINGS.getValue(abbreviation).replace(PLACEHOLDER, expansion)

            val file = createPsiFile(abbreviation, source)
            ensureParsed(file)
            val error = PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java)
            assertNull(
                "the '$abbreviation' template expands to Flix that does not parse: " +
                    "${error?.errorDescription}\n$source",
                error,
            )
        }
    }

    fun testEverySurroundingIsUsedAndEveryTemplateHasOne() {
        val abbreviations = templates().map { it.getAttribute("name") }.toSet()
        assertEquals(
            "a template with no surrounding cannot be parsed, and a surrounding with no template " +
                "is a leftover that would hide the next one going missing",
            abbreviations,
            SURROUNDINGS.keys,
        )
    }

    /** The template's text with every variable replaced by its default. */
    private fun expand(template: Element): String {
        var text = template.getAttribute("value")
        val variables = template.getElementsByTagName("variable")
        for (index in 0 until variables.length) {
            val variable = variables.item(index) as Element
            text = text.replace("\$${variable.getAttribute("name")}$", literal(variable.getAttribute("defaultValue")))
        }
        // $END$ is where the caret lands, not something that is typed.
        return text.replace("\$END$", "")
    }

    /**
     * A default value as it will actually be inserted.
     *
     * The attribute holds a template *expression*, so a constant is written quoted. Leaving the
     * quotes on would parse a Flix identifier as a string and hide the real syntax.
     */
    private fun literal(defaultValue: String): String =
        defaultValue.removeSurrounding("\"")

    private fun templates(): List<Element> {
        val stream = javaClass.getResourceAsStream(TEMPLATE_FILE)
            ?: error("$TEMPLATE_FILE is not on the classpath; the descriptor points at it")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream)
        val nodes = document.getElementsByTagName("template")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private companion object {
        const val TEMPLATE_FILE = "/liveTemplates/Flix.xml"

        /** Where the expansion is spliced into the surrounding source. */
        const val PLACEHOLDER = "<>"

        /** Parsed as written, with nothing around it. */
        const val TOP_LEVEL = PLACEHOLDER

        /** Parsed as the body of a function, which is where an expression can appear. */
        const val IN_A_FUNCTION = "def wrapper(): Unit \\ IO = $PLACEHOLDER"

        val SURROUNDINGS: Map<String, String> = mapOf(
            "def" to TOP_LEVEL,
            "pdef" to TOP_LEVEL,
            "mod" to TOP_LEVEL,
            "enum" to TOP_LEVEL,
            "struct" to TOP_LEVEL,
            "trait" to TOP_LEVEL,
            "instance" to TOP_LEVEL,
            "eff" to TOP_LEVEL,
            "test" to TOP_LEVEL,
        )
    }
}
