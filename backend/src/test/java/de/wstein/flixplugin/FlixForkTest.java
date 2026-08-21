package de.wstein.flixplugin;

import com.intellij.testFramework.HeavyPlatformTestCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Uses HeavyPlatformTestCase (a project backed by real files on disk) rather
 * than the lighter
 * BasePlatformTestCase (an in-memory VFS project, exposed as "temp:/..." URIs)
 * -- FlixFork.resolveJar
 * does plain java.io/java.nio.file filesystem calls against
 * project.getBasePath(), which only work
 * against a real directory.
 */
public class FlixForkTest extends HeavyPlatformTestCase {

    public void testResolvesFlixJarFromTheProjectRoot() throws IOException {
        Path base = Path.of(getProject().getBasePath());
        Files.createDirectories(base);
        Path jar = base.resolve("flix.jar");
        Files.createFile(jar);

        Path resolved = FlixFork.resolveJar(getProject());

        assertEquals(jar.toAbsolutePath().normalize(), resolved.toAbsolutePath().normalize());
    }

    public void testThrowsWhenNoFlixVendorJarIsPresent() {
        try {
            FlixFork.resolveJar(getProject());
            fail("expected IllegalStateException when no flix.jar exists in the project root");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("flix.jar"));
        }
    }

    public void testDoesNotUseAVersionedVendorJarAsTheDefault() throws IOException {
        Path base = Path.of(getProject().getBasePath());
        Files.createDirectories(base);
        Files.createFile(base.resolve("flix-vendor-1.0.0.jar"));
        assertThrows(IllegalStateException.class, () -> FlixFork.resolveJar(getProject()));
    }
}
