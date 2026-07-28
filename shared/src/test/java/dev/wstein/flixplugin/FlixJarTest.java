package dev.wstein.flixplugin;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Which compiler jar gets launched.
 *
 * <p>Every process this plugin starts has to agree on the answer. The language server analyses the
 * editor's buffers with one compiler and the debug configuration runs another, and if those differ
 * the disagreement surfaces as behaviour nobody can explain rather than as an error — so the rule is
 * pinned here rather than left to two implementations that look alike.
 *
 * <p>Plain JUnit against a temporary directory: the rule is filesystem logic and needs no platform.
 * {@code FlixForkTest} still covers the {@code Project}-facing wrapper.
 */
public class FlixJarTest {

    @Rule
    public TemporaryFolder projectRoot = new TemporaryFolder();

    @Test
    public void picksTheMostRecentlyModifiedVendorJar() throws IOException {
        // Rebuilding the fork drops a new jar beside the old one rather than replacing it, so any
        // rule other than "newest" silently keeps running a stale compiler.
        Path older = newJar("flix-vendor-2026.01.01.1.jar", 1_000);
        Path newer = newJar("flix-vendor-2026.07.24.1.jar", 2_000);

        assertEquals(newer, FlixJar.resolve(projectRoot.getRoot().getPath(), null).toAbsolutePath());
        assertTrue(Files.exists(older));
    }

    @Test
    public void ignoresTheProjectWhenAJarIsPinned() throws IOException {
        // FLIX_FORK_JAR is how a developer points the IDE at a working-tree build; it has to win
        // even when the project root holds a perfectly good jar.
        newJar("flix-vendor-2026.07.24.1.jar", 2_000);

        Path pinned = FlixJar.resolve(projectRoot.getRoot().getPath(), "/opt/flix/pinned.jar");

        assertEquals(Path.of("/opt/flix/pinned.jar"), pinned);
    }

    @Test
    public void trimsAPinnedPath() {
        assertEquals(Path.of("/opt/flix/pinned.jar"), FlixJar.resolve(null, "  /opt/flix/pinned.jar  "));
    }

    @Test
    public void treatsABlankPinAsUnset() throws IOException {
        Path jar = newJar("flix-vendor-2026.07.24.1.jar", 2_000);
        assertEquals(jar, FlixJar.resolve(projectRoot.getRoot().getPath(), "   ").toAbsolutePath());
    }

    @Test
    public void namesTheRemedyWhenNoJarIsFound() {
        // The user cannot act on "not found". They can act on "build the fork or set this variable".
        var thrown = assertThrows(
                IllegalStateException.class,
                () -> FlixJar.resolve(projectRoot.getRoot().getPath(), null));
        assertTrue(thrown.getMessage().contains("flix-vendor-*.jar"));
        assertTrue(thrown.getMessage().contains(FlixJar.PINNED_JAR_ENV));
    }

    @Test
    public void namesTheRemedyWhenTheProjectHasNoBasePath() {
        var thrown = assertThrows(IllegalStateException.class, () -> FlixJar.resolve(null, null));
        assertTrue(thrown.getMessage().contains(FlixJar.PINNED_JAR_ENV));
    }

    @Test
    public void ignoresFilesThatAreNotVendorJars() throws IOException {
        newJar("flix-vendor-2026.01.01.1.jar", 1_000);
        // Newer, but neither a vendor jar nor a jar at all -- picking either would launch something
        // that is not a compiler.
        newJar("some-other.jar", 9_000);
        newJar("flix-vendor-notes.txt", 9_000);

        assertEquals(
                "flix-vendor-2026.01.01.1.jar",
                FlixJar.resolve(projectRoot.getRoot().getPath(), null).getFileName().toString());
    }

    private Path newJar(String name, long modifiedMillis) throws IOException {
        Path path = projectRoot.getRoot().toPath().resolve(name);
        Files.createFile(path);
        Files.setLastModifiedTime(path, FileTime.fromMillis(modifiedMillis));
        return path.toAbsolutePath();
    }
}
