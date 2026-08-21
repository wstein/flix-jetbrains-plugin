package de.wstein.flixplugin;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * That the type a {@code --Xdebug} build recorded reaches a reader, and that a missing one is
 * answered as missing.
 *
 * <p>The fixtures are the compiler's own output shape, copied from what a build writes. The
 * compiler side has its own tests over the same format, so a change to it fails on both sides
 * rather than silently on neither.
 */
public class FlixDebugScopesTest {

    @Rule
    public final TemporaryFolder projectRoot = new TemporaryFolder();

    /**
     * Two classes, one with two methods, with the escapes and nesting a real file has.
     *
     * <p>The {@code def}, {@code source} and {@code sourceAt} keys are here because a real file has
     * them, not because this reader wants them: it takes types out of the table and leaves the
     * definition's identity to the compiler, which is the side that can act on it. They are still
     * the shape this must survive -- a string-valued key beside the method arrays -- and the lifted
     * lambda carries no source at all, which is the other shape.
     */
    private static final String SCOPES = """
            {
              "formatVersion":2,
              "classes":{
                "dev.flix.gen.Clo$main$626ZYxrpg1N": {
                  "def": "main$626ZYxrpg1N",
                  "staticApply": [{"name":"prefix","type":"String"},{"name":"x","type":"Int32"}]
                },
                "dev.flix.gen.Def$describe": {
                  "def": "describe",
                  "source": "describe",
                  "sourceAt": "Main.flix:1:5",
                  "applyFrame": [{"name":"at","type":"Option[String]"}],
                  "staticApply": [{"name":"at","type":"Option[String]"},{"name":"pair","type":"(Int32, Bool)"}]
                }
              }
            }
            """;

    @Test
    public void aRecordedTypeIsFoundByClassMethodAndName() {
        FlixDebugScopes scopes = write(SCOPES);

        assertEquals("Option[String]", scopes.typeOf("dev.flix.gen.Def$describe", "staticApply", "at"));
        assertEquals("(Int32, Bool)", scopes.typeOf("dev.flix.gen.Def$describe", "staticApply", "pair"));
    }

    @Test
    public void aCaptureIsFoundInTheClassTheLambdaWasLiftedTo() {
        // The name that reads as `clo0` in the bytecode, and as nothing at all before this.
        FlixDebugScopes scopes = write(SCOPES);

        assertEquals("String", scopes.typeOf("dev.flix.gen.Clo$main$626ZYxrpg1N", "staticApply", "prefix"));
    }

    @Test
    public void theMethodIsPartOfTheKey() {
        // A definition's parameters live in `staticApply` when it compiles to a static method and in
        // `applyFrame` when it becomes a continuation. Reading one table for the other method would
        // report a type for a frame that does not hold that name.
        FlixDebugScopes scopes = write(SCOPES);

        assertEquals("Option[String]", scopes.typeOf("dev.flix.gen.Def$describe", "applyFrame", "at"));
        assertNull(scopes.typeOf("dev.flix.gen.Def$describe", "applyFrame", "pair"));
    }

    @Test
    public void aNameNobodyRecordedIsAnsweredAsMissing() {
        // The ordinary case for a `let`-bound local, which this table does not cover. Answering
        // anything else would be reporting a type nobody wrote down.
        FlixDebugScopes scopes = write(SCOPES);

        assertNull(scopes.typeOf("dev.flix.gen.Def$describe", "staticApply", "somethingElse"));
        assertNull(scopes.typeOf("dev.flix.gen.Def$nobody", "staticApply", "at"));
        assertNull(scopes.typeOf("dev.flix.gen.Def$describe", "noSuchMethod", "at"));
    }

    @Test
    public void classesAreKeptApart() {
        // The parse walks a nested object with a flat pattern, so the failure to guard against is
        // one class's methods being attributed to the class before it.
        FlixDebugScopes scopes = write(SCOPES);

        assertNull(
                "a method of one class was attributed to another",
                scopes.typeOf("dev.flix.gen.Clo$main$626ZYxrpg1N", "applyFrame", "at"));
        assertEquals(2, scopes.size());
    }

    @Test
    public void aBuildWithNoTableIsEmptyRatherThanAnError() {
        // No build yet, a build without --Xdebug, or a compiler that predates the table. None is an
        // error: the caller falls back to the erased type, which is what every debugger had before.
        FlixDebugScopes scopes = FlixDebugScopes.read(projectRoot.getRoot().toPath());

        assertTrue(scopes.isEmpty());
        assertNull(scopes.typeOf("dev.flix.gen.Def$describe", "staticApply", "at"));
    }

    @Test
    public void aTableFromAnotherFormatIsIgnored() {
        // Rather than parsed as though it were this one. A reader that guessed would report types
        // from a shape it does not understand.
        FlixDebugScopes scopes = write(SCOPES.replace("\"formatVersion\":2", "\"formatVersion\":3"));

        assertTrue(scopes.isEmpty());
    }

    @Test
    public void theTableIsReadFromWhereTheBuildWritesIt() {
        // Beside the build manifest, not inside the class directory -- which is reconciled against
        // the class files a build produced.
        assertEquals("build/development/debug-scopes.json", FlixDebugScopes.SCOPES_PATH);

        FlixDebugScopes scopes = write(SCOPES);
        assertFalse("nothing was read from the documented path", scopes.isEmpty());
    }

    private FlixDebugScopes write(String contents) {
        try {
            Path path = projectRoot.getRoot().toPath().resolve(FlixDebugScopes.SCOPES_PATH);
            Files.createDirectories(path.getParent());
            Files.writeString(path, contents);
            return FlixDebugScopes.read(projectRoot.getRoot().toPath());
        } catch (IOException e) {
            throw new AssertionError("could not write the fixture", e);
        }
    }
}
