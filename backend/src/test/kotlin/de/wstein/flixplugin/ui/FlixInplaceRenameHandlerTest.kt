package de.wstein.flixplugin.ui

import com.intellij.codeInsight.template.Template
import com.intellij.codeInsight.template.impl.TemplateImpl
import com.intellij.openapi.util.TextRange
import com.intellij.refactoring.rename.inplace.VariableInplaceRenameHandler
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a rename may be done in place, and what the template it builds actually contains.
 *
 * A template edits the open document only, and the moment it does, the server's analysis of that
 * file is stale -- so a follow-up rename for the remaining files would be computed against source
 * that no longer exists. A symbol confined to one file can therefore be renamed in place; anything
 * wider is handed to the dialog, which applies one edit atomically.
 */
class FlixInplaceRenameHandlerTest {

    private val here = "/proj/src/Main.flix"
    private val elsewhere = "/proj/src/Other.flix"

    private fun location(path: String, line: Int): Location =
        Location("file://$path", Range(Position(line, 0), Position(line, 4)))

    @Test
    fun `a symbol confined to the open file can be renamed in place`() {
        val locations = listOf(location(here, 1), location(here, 7))
        assertEquals(2, FlixInplaceRenameHandler.locationsIn(locations, here)?.size)
    }

    @Test
    fun `a symbol used in another file is refused, not partially renamed`() {
        // Filtering to the open file would rewrite it and leave Other.flix calling a name that no
        // longer exists. Refusing here is what sends it to the dialog instead.
        val locations = listOf(location(here, 1), location(elsewhere, 3))
        assertNull(FlixInplaceRenameHandler.locationsIn(locations, here))
    }

    @Test
    fun `no occurrences at all is refused`() {
        // The server found nothing to rename; there is no template to build.
        assertNull(FlixInplaceRenameHandler.locationsIn(emptyList(), here))
    }

    @Test
    fun `a file URI is compared by path, not by spelling`() {
        // The server echoes back the URI the client sent, and a VirtualFile path is what we hold.
        assertEquals("/proj/src/Main.flix", FlixInplaceRenameHandler.pathOf("file:///proj/src/Main.flix"))
        // Anything that is not a URI is its own path, rather than throwing mid-rename.
        assertEquals("/proj/src/Main.flix", FlixInplaceRenameHandler.pathOf("/proj/src/Main.flix"))
    }

    @Test
    fun `LSP4IJ only stands down for an in-place renamer`() {
        // LSPRenameHandler.isAvailableOnDataContext re-enters RenameHandlerRegistry and returns
        // false only when every other available handler is a VariableInplaceRenameHandler. Break
        // this and both handlers stay available, so the platform asks the user which one they meant
        // before every rename.
        assertTrue(VariableInplaceRenameHandler::class.java.isAssignableFrom(FlixInplaceRenameHandler::class.java))
    }

    @Test
    fun `each occurrence becomes exactly one template variable`() {
        // TemplateImpl.addVariable emits the variable's first segment itself, so adding one for the
        // first occurrence as well put two segments at the same offset and typing `sum` rendered
        // `sumsum`.
        val template = template(Source, Occurrences)
        assertEquals(Occurrences.size, template.segmentsCount)
        assertEquals(0, template.getSegmentOffset(0))
        assertEquals("(a: Int32): Int32 = ", template.templateText)
    }

    @Test
    fun `typing a new name rewrites every occurrence and nothing else`() {
        // The span the template replaces runs from the first occurrence to the last, so this is the
        // whole of what the editor ends up showing between them.
        assertEquals("sum(a: Int32): Int32 = sum", render(template(Source, Occurrences), "sum"))
    }

    @Test
    fun `an occurrence reported twice is rewritten once`() {
        // A declaration and a reference can name the same span. Two segments over one span would
        // render the new name twice, which is the same defect from a different direction.
        val duplicated = listOf(TextRange(27, 30), TextRange(4, 7), TextRange(4, 7))
        assertEquals(Occurrences, FlixInplaceRenameHandler.normalized(duplicated))
    }

    /** A template over [ranges] of [text], built the way the handler builds it. */
    private fun template(text: String, ranges: List<TextRange>): Template =
        TemplateImpl("", "").also { FlixInplaceRenameHandler.fillTemplate(it, text, ranges, "add") }

    /** What the editor shows once every variable segment holds [newName]. */
    private fun render(template: Template, newName: String): String {
        val text = template.templateText
        val rendered = StringBuilder()
        var cursor = 0
        for (segment in 0 until template.segmentsCount) {
            val offset = template.getSegmentOffset(segment)
            rendered.append(text, cursor, offset).append(newName)
            cursor = offset
        }
        return rendered.append(text, cursor, text.length).toString()
    }

    private companion object {
        /** `add` is declared at 4 and called at 27. */
        const val Source = "def add(a: Int32): Int32 = add(a)"
        val Occurrences = listOf(TextRange(4, 7), TextRange(27, 30))
    }
}
