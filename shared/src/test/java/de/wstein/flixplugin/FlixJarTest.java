package de.wstein.flixplugin;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Which compiler jar gets launched.
 *
 * <p>
 * Every process this plugin starts has to agree on the answer. The language
 * server analyses the
 * editor's buffers with one compiler and the debug configuration runs another,
 * and if those differ
 * the disagreement surfaces as behaviour nobody can explain rather than as an
 * error — so the rule is
 * pinned here rather than left to two implementations that look alike.
 *
 * <p>
 * Plain JUnit against a temporary directory: the rule is filesystem logic and
 * needs no platform.
 * {@code FlixForkTest} still covers the {@code Project}-facing wrapper.
 */
public class FlixJarTest {

    private static final String DIGEST = "a2697d875725a0dde6e793b8d54cb220e86167a6d49ec5f0ccb0832966c8c15a";

    @Rule
    public TemporaryFolder projectRoot = new TemporaryFolder();

    @Test
    public void picksFlixJarFromTheProjectRoot() throws IOException {
        Path jar = newJar("flix.jar");

        assertEquals(jar, FlixJar.resolve(projectRoot.getRoot().getPath(), null).toAbsolutePath());
    }

    @Test
    public void ignoresTheProjectWhenAJarIsPinned() throws IOException {
        // FLIX_JAR is how a developer points the IDE at a working-tree build; it has to
        // win
        // even when the project root holds a perfectly good jar.
        newJar("flix.jar");

        Path pinned = FlixJar.resolve(projectRoot.getRoot().getPath(), "/opt/flix/pinned.jar");

        assertEquals(Path.of("/opt/flix/pinned.jar"), pinned);
    }

    @Test
    public void trimsAPinnedPath() {
        assertEquals(Path.of("/opt/flix/pinned.jar"), FlixJar.resolve(null, "  /opt/flix/pinned.jar  "));
    }

    @Test
    public void treatsABlankPinAsUnset() throws IOException {
        Path jar = newJar("flix.jar");
        assertEquals(jar, FlixJar.resolve(projectRoot.getRoot().getPath(), "   ").toAbsolutePath());
    }

    @Test
    public void namesTheRemedyWhenNoJarIsFound() {
        // The user cannot act on "not found". They can act on "build the fork or set
        // this variable".
        var thrown = assertThrows(
                IllegalStateException.class,
                () -> FlixJar.resolve(projectRoot.getRoot().getPath(), null));
        assertTrue(thrown.getMessage().contains("flix.jar"));
        assertTrue(thrown.getMessage().contains(FlixJar.JAR_ENV));
    }

    @Test
    public void namesTheRemedyWhenTheProjectHasNoBasePath() {
        var thrown = assertThrows(IllegalStateException.class, () -> FlixJar.resolve(null, null));
        assertTrue(thrown.getMessage().contains(FlixJar.JAR_ENV));
    }

    @Test
    public void doesNotUseAVersionedVendorJarAsTheDefault() throws IOException {
        newJar("flix-vendor-2026.07.24.1.jar");

        var thrown = assertThrows(
                IllegalStateException.class,
                () -> FlixJar.resolve(projectRoot.getRoot().getPath(), null));
        assertTrue(thrown.getMessage().contains("flix.jar"));
    }

    @Test
    public void explicitEnvironmentOverrideWinsOverFlixwLocalSelection() throws IOException {
        Path root = projectRoot.getRoot().toPath();
        Files.writeString(root.resolve(FlixwProject.WRAPPER_POSIX), "#!/bin/sh\n");
        Files.createDirectories(root.resolve(".flixw/local"));
        Files.writeString(root.resolve(FlixwProject.LOCK), """
                [compiler]
                version = "0.75.2"
                sha256 = "%s"
                """.formatted(DIGEST));
        Path local = newJar("local.jar");
        Files.writeString(root.resolve(FlixwProject.LOCAL_COMPILER), """
                path = "%s"
                selected_sha256 = "%s"
                """.formatted(local, DIGEST));

        assertEquals(Path.of("/opt/flix/temporary.jar"),
                FlixJar.resolve(root.toString(), "/opt/flix/temporary.jar"));
    }

    @Test
    public void projectEnvrcWinsOverAmbientAndFlixwLocalSelection() throws IOException {
        Path root = projectRoot.getRoot().toPath();
        Files.writeString(root.resolve(FlixwProject.WRAPPER_POSIX), "#!/bin/sh\n");
        Files.createDirectories(root.resolve(".flixw/local"));
        Files.writeString(root.resolve(FlixwProject.LOCK), """
                [compiler]
                version = "0.75.2"
                sha256 = "%s"
                """.formatted(DIGEST));
        Path local = newJar("local.jar");
        Files.writeString(root.resolve(FlixwProject.LOCAL_COMPILER), """
                path = "%s"
                selected_sha256 = "%s"
                """.formatted(local, DIGEST));
        Path envrc = newJar("envrc.jar");
        Files.writeString(root.resolve(FlixEnvrc.ENVRC), "export FLIX_JAR=\"%s\"\n".formatted(envrc));

        assertEquals(envrc, FlixJar.resolve(root.toString(), "/opt/flix/ambient.jar"));
    }

    private Path newJar(String name) throws IOException {
        Path path = projectRoot.getRoot().toPath().resolve(name);
        Files.createFile(path);
        return path.toAbsolutePath();
    }
}
