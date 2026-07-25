package dev.wstein.flixplugin;

import com.intellij.testFramework.HeavyPlatformTestCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/**
 * Uses HeavyPlatformTestCase (a project backed by real files on disk) rather than the lighter
 * BasePlatformTestCase (an in-memory VFS project, exposed as "temp:/..." URIs) -- FlixFork.resolveJar
 * does plain java.io/java.nio.file filesystem calls against project.getBasePath(), which only work
 * against a real directory.
 */
public class FlixForkTest extends HeavyPlatformTestCase {

    public void testResolvesTheMostRecentlyModifiedFlixVendorJar() throws IOException {
        Path base = Path.of(getProject().getBasePath());
        Files.createDirectories(base);
        Path older = base.resolve("flix-vendor-2026.01.01.1.jar");
        Path newer = base.resolve("flix-vendor-2026.07.24.1.jar");
        Files.createFile(older);
        Files.createFile(newer);
        Files.setLastModifiedTime(older, FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(newer, FileTime.fromMillis(2_000));

        Path resolved = FlixFork.resolveJar(getProject());

        assertEquals(newer.toAbsolutePath().normalize(), resolved.toAbsolutePath().normalize());
    }

    public void testThrowsWhenNoFlixVendorJarIsPresent() {
        try {
            FlixFork.resolveJar(getProject());
            fail("expected IllegalStateException when no flix-vendor-*.jar exists in the project root");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("flix-vendor-*.jar"));
        }
    }

    public void testIgnoresFilesNotMatchingTheFlixVendorJarPattern() throws IOException {
        Path base = Path.of(getProject().getBasePath());
        Files.createDirectories(base);
        Files.createFile(base.resolve("some-other-library.jar"));
        Files.createFile(base.resolve("flix-vendor-1.0.0.jar.sha256"));
        Path onlyMatch = base.resolve("flix-vendor-1.0.0.jar");
        Files.createFile(onlyMatch);

        Path resolved = FlixFork.resolveJar(getProject());

        assertEquals(onlyMatch.toAbsolutePath().normalize(), resolved.toAbsolutePath().normalize());
    }
}
