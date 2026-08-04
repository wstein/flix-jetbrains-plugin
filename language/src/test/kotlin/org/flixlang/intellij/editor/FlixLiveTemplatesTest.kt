package org.flixlang.intellij.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The Flix live templates, read as the platform reads them.
 *
 * Two things here are matched by string and nothing else checks them: the context id in the
 * descriptor against the one every template names, and the abbreviation against what the language
 * server already completes. Getting either wrong is silent — templates that never trigger, or a
 * construct offered twice from two lists that then drift.
 */
class FlixLiveTemplatesTest {

    private val templates: List<Element> = run {
        val stream = javaClass.getResourceAsStream("/liveTemplates/Flix.xml")
            ?: error("the template file is not on the classpath; the descriptor points at it")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream)
        val nodes = document.getElementsByTagName("template")
        (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun `there are templates at all`() {
        assertTrue("the template file declares none", templates.isNotEmpty())
    }

    @Test
    fun `every template is enabled in the Flix context and no other`() {
        // The id is the one FlixTemplateContextType passes to its superclass. A mismatch leaves the
        // template registered, listed in Settings, and never offered.
        templates.forEach { template ->
            val options = template.getElementsByTagName("option")
            val names = (0 until options.length).map { (options.item(it) as Element).getAttribute("name") }
            assertEquals("${template.getAttribute("name")} names the wrong context", listOf(CONTEXT_ID), names)
        }
    }

    @Test
    fun `no template duplicates a snippet the server already completes`() {
        // ExprSnippetCompleter offers `main` and a default effect handler. Shipping either here
        // would put one construct in the completion list twice, from two inventories.
        val abbreviations = templates.map { it.getAttribute("name") }
        assertFalse("`main` is already a server snippet: $abbreviations", abbreviations.contains("main"))
    }

    @Test
    fun `abbreviations are distinct`() {
        val abbreviations = templates.map { it.getAttribute("name") }
        assertEquals(abbreviations.size, abbreviations.toSet().size)
    }

    @Test
    fun `every template describes itself and stops somewhere for the user to type`() {
        templates.forEach { template ->
            val name = template.getAttribute("name")
            assertFalse("$name has no description", template.getAttribute("description").isBlank())
            // A template with no stop expands and hands the caret back at the end, which for a
            // declaration means outside the block it just opened. Whether the expansion is *valid*
            // is FlixLiveTemplateSyntaxTest's question, not this one.
            val stops = template.getElementsByTagName("variable").length
            assertTrue("$name has nothing for the user to fill in", stops > 0)
        }
    }

    private companion object {
        /** Must equal what `FlixTemplateContextType` passes to `TemplateContextType`. */
        const val CONTEXT_ID = "Flix"
    }
}
