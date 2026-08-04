package dev.wstein.flixplugin.ui

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * When a rename may be done in place.
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
}
