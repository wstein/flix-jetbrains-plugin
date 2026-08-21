package de.wstein.flixplugin;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * What is understood in an {@code .envrc}, and what is deliberately not.
 *
 * <p>
 * The lines exercised here are the ones flixw's own {@code .envrc.example}
 * writes, uncommented.
 * That file is the reason this parser exists, so it is the specification it is
 * written against.
 */
public class FlixEnvrcTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path project(String envrc) throws Exception {
        Path root = folder.newFolder("project").toPath();
        Files.writeString(root.resolve(FlixEnvrc.ENVRC), envrc, StandardCharsets.UTF_8);
        return root;
    }

    @Test
    public void readsAPlainExport() throws Exception {
        Path root = project("export FLIX_JVM_OPTS=\"-Xmx4g\"\n");

        assertEquals("-Xmx4g", FlixEnvrc.valueOf(root, "FLIX_JVM_OPTS"));
    }

    /**
     * {@code $PWD} is the project, not this process's directory.
     *
     * <p>
     * direnv evaluates the file after entering the directory, and the template's
     * own cache
     * example relies on it. Using the IDE's working directory would put a
     * project-local cache
     * wherever the IDE happened to be launched from -- which on macOS is {@code /}.
     */
    @Test
    public void expandsPwdToTheProject() throws Exception {
        Path root = project("export FLIX_CACHE_HOME=\"$PWD/.flixw/local/cache\"\n");

        assertEquals(root.toAbsolutePath() + "/.flixw/local/cache", FlixEnvrc.valueOf(root, "FLIX_CACHE_HOME"));
    }

    @Test
    public void expandsHomeAndBraces() throws Exception {
        Path root = project("export FLIX_JAVA_HOME=\"$HOME/.sdkman/candidates/java/21.0.5-tem\"\n"
                + "export A=\"${HOME}/x\"\n");
        String home = System.getProperty("user.home");

        assertEquals(home + "/.sdkman/candidates/java/21.0.5-tem", FlixEnvrc.valueOf(root, "FLIX_JAVA_HOME"));
        assertEquals(home + "/x", FlixEnvrc.valueOf(root, "A"));
    }

    /**
     * Single quotes are literal, as they are in the bash direnv evaluates this
     * with.
     */
    @Test
    public void singleQuotesSuppressExpansion() throws Exception {
        Path root = project("export A='$HOME/x'\n");

        assertEquals("$HOME/x", FlixEnvrc.valueOf(root, "A"));
    }

    /**
     * A later assignment may build on an earlier one, which is what a script would
     * do.
     */
    @Test
    public void anAssignmentCanUseAnEarlierOne() throws Exception {
        Path root = project("export BASE=\"/opt/flix\"\nexport FLIX_JAR=\"$BASE/flix.jar\"\n");

        assertEquals("/opt/flix/flix.jar", FlixEnvrc.valueOf(root, "FLIX_JAR"));
    }

    /**
     * Every line of the template is commented, so a file straight from `install`
     * exports nothing.
     */
    @Test
    public void commentsAndBlanksExportNothing() throws Exception {
        Path root = project("# export FLIX_JAR=\"/should/not/be/read\"\n\n   # another\n");

        assertNull(FlixEnvrc.valueOf(root, "FLIX_JAR"));
        assertEquals(0, FlixEnvrc.read(root).size());
    }

    @Test
    public void dropsATrailingCommentButNotAHashInAValue() throws Exception {
        Path root = project("export A=/opt/flix   # where it lives\nexport B=https://x/y#frag\n");

        assertEquals("/opt/flix", FlixEnvrc.valueOf(root, "A"));
        assertEquals("https://x/y#frag", FlixEnvrc.valueOf(root, "B"));
    }

    /**
     * Inside quotes a {@code #} is an ordinary character, whitespace before it or
     * not.
     *
     * <p>
     * The comment used to be stripped before the quotes were read, which truncated
     * the value at
     * the {@code #} and left the opening quote attached to what survived -- so the
     * path was both
     * wrong and unopenable.
     */
    @Test
    public void aHashInsideQuotesIsPartOfTheValue() throws Exception {
        Path root = project("export A=\"/opt/my #1 build/flix.jar\"\n"
                + "export B='/opt/other #2/flix.jar'   # and this one is a comment\n");

        assertEquals("/opt/my #1 build/flix.jar", FlixEnvrc.valueOf(root, "A"));
        assertEquals("/opt/other #2/flix.jar", FlixEnvrc.valueOf(root, "B"));
    }

    /**
     * A conditional body is not read, however it is laid out.
     *
     * <p>
     * The single-line form was already skipped, because a line beginning with
     * {@code if} is not
     * an assignment. The indented form matched the assignment pattern perfectly and
     * was taken
     * unconditionally -- so an {@code .envrc} guarding a Linux path with
     * {@code uname} pointed the
     * IDE at that path on macOS, where the shell would have skipped it.
     */
    @Test
    public void anIndentedConditionalBodyIsNotRead() throws Exception {
        Path root = project("""
                if [ "$(uname)" = "Linux" ]; then
                  export FLIX_JAR=/opt/flix/flix.jar
                fi
                export AFTER=1
                """);

        assertNull(FlixEnvrc.valueOf(root, "FLIX_JAR"));
        assertEquals("1", FlixEnvrc.valueOf(root, "AFTER"));
    }

    /**
     * Loops and `case` are bodies too, and nesting has to come back to zero exactly
     * once.
     */
    @Test
    public void loopsAndNestingAreTrackedToo() throws Exception {
        Path root = project("""
                for d in a b; do
                  if [ -d "$d" ]; then
                    export INNER=1
                  fi
                  export OUTER=1
                done
                export AFTER=2
                """);

        assertNull(FlixEnvrc.valueOf(root, "INNER"));
        assertNull(FlixEnvrc.valueOf(root, "OUTER"));
        assertEquals("2", FlixEnvrc.valueOf(root, "AFTER"));
    }

    /**
     * A block opened and closed on one line must not suppress everything after it.
     *
     * <p>
     * Counting openers at the start of a line rather than as occurrences would
     * leave the depth
     * stuck at one from the first single-line {@code if} onwards, silently dropping
     * the rest of the
     * file -- a much larger failure than the one being fixed.
     */
    @Test
    public void aSingleLineBlockDoesNotSwallowTheRestOfTheFile() throws Exception {
        Path root = project("if [ -f /tmp/x ]; then export A=1; fi\nexport FLIX_JAR=\"/still/read\"\n");

        assertNull(FlixEnvrc.valueOf(root, "A"));
        assertEquals("/still/read", FlixEnvrc.valueOf(root, "FLIX_JAR"));
    }

    /**
     * direnv's own include, which the template suggests for machine-specific
     * values.
     */
    @Test
    public void followsSourceEnvIfExists() throws Exception {
        Path root = project("export A=1\nsource_env_if_exists .envrc.local\n");
        Files.writeString(root.resolve(".envrc.local"), "export FLIX_JAR=\"/from/local\"\n", StandardCharsets.UTF_8);

        assertEquals("/from/local", FlixEnvrc.valueOf(root, "FLIX_JAR"));
        assertEquals("1", FlixEnvrc.valueOf(root, "A"));
    }

    @Test
    public void anAbsentIncludeIsNotAFailure() throws Exception {
        Path root = project("source_env_if_exists .envrc.local\nexport A=1\n");

        assertEquals("1", FlixEnvrc.valueOf(root, "A"));
    }

    /**
     * Nothing is executed, so what a command would have produced is simply not
     * known.
     *
     * <p>
     * This is the floor the design accepts in exchange for not running a project's
     * shell script
     * on open. It is asserted rather than left implicit: someone will eventually
     * wonder why a value
     * did not arrive, and this is the answer.
     */
    @Test
    public void whatIsNotExecutedIsNotKnown() throws Exception {
        Path root = project("export FLIX_JAR=\"$(pwd)/flix.jar\"\n"
                + "if [ -f /tmp/x ]; then export A=1; fi\n");

        // Recorded verbatim rather than run -- and it is not a usable path, which is
        // the point.
        assertEquals("$(pwd)/flix.jar", FlixEnvrc.valueOf(root, "FLIX_JAR"));
        assertNull(FlixEnvrc.valueOf(root, "A"));
    }

    @Test
    public void aProjectWithoutTheFileExportsNothing() throws Exception {
        assertEquals(0, FlixEnvrc.read(folder.newFolder("bare").toPath()).size());
        assertEquals(0, FlixEnvrc.read(null).size());
        assertNull(FlixEnvrc.valueOf(null, "FLIX_JAR"));
    }

    /**
     * An unresolved reference is left as written rather than becoming a plausible
     * wrong path.
     */
    @Test
    public void anUnknownReferenceIsLeftAlone() throws Exception {
        Path root = project("export A=\"$NOT_SET_ANYWHERE_12345/x\"\n");

        assertEquals("$NOT_SET_ANYWHERE_12345/x", FlixEnvrc.valueOf(root, "A"));
    }

    /**
     * `.envrc` wins over the ambient variable, as it would in a terminal.
     *
     * <p>
     * direnv overwrites the inherited value on entering the directory. Deferring to
     * the ambient
     * one here would make the IDE the only place a project's own choice loses to a
     * global default.
     */
    @Test
    public void theProjectFileOutranksTheAmbientVariable() throws Exception {
        Path root = project("export FLIX_JAR=\"$PWD/from-envrc.jar\"\n");
        Path jar = Files.createFile(root.resolve("from-envrc.jar"));

        assertEquals(jar, FlixJar.resolve(root.toString(), "/from/environment"));
    }

    /**
     * An {@code .envrc} naming a jar that is not there fails by name rather than by
     * silence.
     *
     * <p>
     * It outranks {@code $FLIX_JAR}, a flixw pin and the project's own jar, so
     * falling through
     * would launch a compiler the project did not ask for -- and returning the path
     * unchecked
     * deferred the failure to {@code java -jar}, which reports it as a missing file
     * with no mention
     * of the file that named it.
     */
    @Test
    public void aStaleProjectFileSaysSoRatherThanShadowingAGoodJar() throws Exception {
        Path root = project("export FLIX_JAR=\"/gone/stale.jar\"\n");
        Files.createFile(root.resolve("flix.jar"));

        var thrown = assertThrows(
                IllegalStateException.class,
                () -> FlixJar.resolve(root.toString(), null));

        assertTrue(thrown.getMessage().contains(FlixEnvrc.ENVRC));
        assertTrue(thrown.getMessage().contains("/gone/stale.jar"));
    }
}
