package dev.wstein.flixplugin.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which name a Show Flix Diagram invocation asks the server about.
 *
 * The caret is wherever the user left it, which is rarely the start of the word: clicking lands it
 * mid-word and double-clicking lands it at the end. Reading the identifier from only one side would
 * work in testing and fail in use.
 */
class FlixShowDiagramActionTest {

    private fun at(text: String): String? {
        val caret = text.indexOf('|')
        return FlixShowDiagramAction.identifierAt(text.removeRange(caret, caret + 1), caret)
    }

    @Test
    fun `reads the identifier from anywhere inside it`() {
        assertEquals("Equatable", at("pub trait |Equatable[a] {"))
        assertEquals("Equatable", at("pub trait Equa|table[a] {"))
        // Where the caret lands after double-clicking the word.
        assertEquals("Equatable", at("pub trait Equatable| [a] {"))
    }

    @Test
    fun `accepts the characters a Flix name may contain`() {
        assertEquals("is_prime!", at("def is_pr|ime!(): Bool"))
        assertEquals("Int32", at("x: In|t32"))
    }

    @Test
    fun `stops at characters that cannot be part of a name`() {
        // Qualified names are separated by dots; the server is asked about the part under the
        // caret, not the whole path.
        assertEquals("Hello", at("OpenRewrite.He|llo.main"))
        assertEquals("main", at("OpenRewrite.Hello.mai|n"))
    }

    @Test
    fun `finds nothing where there is no identifier`() {
        assertNull(at("    |    "))
        assertNull(at("x + |+ y"))
    }

    @Test
    fun `a caret past the end of the text is not an error`() {
        assertNull(FlixShowDiagramAction.identifierAt("", 5))
        assertEquals("abc", FlixShowDiagramAction.identifierAt("abc", 99))
    }
}
