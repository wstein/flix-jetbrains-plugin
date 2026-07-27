package dev.wstein.flixplugin;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * How the {@code flix.runMain} CodeLens argument is interpreted.
 *
 * <p>The Flix language server sends a "Run" CodeLens for <em>every</em> entry point in a file, each
 * carrying its own symbol, so dropping the argument makes the lens above one entry point run a
 * different one -- silently, and only visibly wrong once a project has more than one.
 *
 * <p>The value arrives as JSON from a server this plugin does not control, so these are as much
 * about malformed input as the happy path: anything unusable has to degrade to "run the project
 * default" rather than emit {@code --entrypoint} with nothing after it, which the compiler rejects
 * with an error the user cannot act on.
 */
public class FlixRunMainActionTest {

    @Test
    public void readsAQualifiedEntryPointSymbol() {
        // Exactly the shape Symbol.DefnSym.toString produces, and exactly what --entrypoint's
        // Symbol.mkDefnSym parses back.
        assertEquals("Main.main", FlixRunMainAction.entryPointOf("Main.main"));
    }

    @Test
    public void readsAnUnqualifiedSymbol() {
        // DefnSym.toString omits the namespace separator when there is no namespace.
        assertEquals("main", FlixRunMainAction.entryPointOf("main"));
    }

    @Test
    public void readsANestedNamespace() {
        // mkDefnSym's split takes everything before the *last* dot as the namespace, so a nested
        // one has to survive intact rather than being truncated to its first segment.
        assertEquals("Foo.Bar.demo", FlixRunMainAction.entryPointOf("Foo.Bar.demo"));
    }

    @Test
    public void fallsBackToTheProjectDefaultWithoutAnArgument() {
        // A server predating the argument, or the action invoked by hand from Find Action.
        assertNull(FlixRunMainAction.entryPointOf(null));
    }

    @Test
    public void fallsBackWhenTheArgumentIsBlank() {
        assertNull(FlixRunMainAction.entryPointOf(""));
        assertNull(FlixRunMainAction.entryPointOf("   "));
    }

    @Test
    public void fallsBackWhenTheArgumentIsNotAString() {
        // The wire format is JSON; a protocol change could deliver a number, a list or an object.
        assertNull(FlixRunMainAction.entryPointOf(42));
        assertNull(FlixRunMainAction.entryPointOf(List.of("Main.main")));
    }

    @Test
    public void trimsSurroundingWhitespace() {
        assertEquals("Main.main", FlixRunMainAction.entryPointOf("  Main.main  "));
    }
}
