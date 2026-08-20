package dev.wstein.flixplugin;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Reading the launch spec the compiler records.
 *
 * <p>This is a file format shared with another repository, and the failures it can produce are all
 * quiet: a classpath read in the wrong order shadows the program's own classes with a dependency's,
 * a dropped entry becomes {@code NoClassDefFoundError} at run time, and a manifest from an older
 * compiler that was read anyway would start the program with two thirds of a command line. So the
 * assertions here are mostly about refusing, not about parsing.
 */
public class FlixBuildSpecTest {

    private static final String MANIFEST = """
            {
              "formatVersion":4,
              "compilerVersion":"0.75.2+fork.wstein",
              "fingerprint":"f",
              "frontendFingerprint":"ff",
              "products":["Main.class"],
              "sources":["src/Main.flix"],
              "sourcesDigest":"d",
              "hasMain":true,
              "launch":{
                "java":"/opt/jdk/bin/java",
                "mainClass":"Main",
                "runtimeClasspath":["/p/build/development/class","/p/lib/external/core.jar"]
              }
            }
            """;

    private Path projectWith(String manifest) throws IOException {
        Path root = Files.createTempDirectory("flix-spec");
        Path file = FlixBuildSpec.manifestIn(root);
        Files.createDirectories(file.getParent());
        Files.writeString(file, manifest);
        return root;
    }

    @Test
    public void readsWhatTheCompilerWrote() throws IOException {
        FlixBuildSpec spec = FlixBuildSpec.read(projectWith(MANIFEST));

        assertEquals("/opt/jdk/bin/java", spec.java());
        assertEquals("Main", spec.mainClass());
        assertEquals(List.of("/p/build/development/class", "/p/lib/external/core.jar"), spec.runtimeClasspath());
    }

    @Test
    public void keepsTheClasspathInOrder() throws IOException {
        // A classpath is ordered. The class directory has to come first, or a dependency shipping a
        // class of the same name shadows the program's own -- which fails at run time, in the
        // program, with nothing pointing at the manifest.
        FlixBuildSpec spec = FlixBuildSpec.read(projectWith(MANIFEST));
        assertTrue(spec.classpath().indexOf("class") < spec.classpath().indexOf("core.jar"));
    }

    @Test
    public void aBuildWithNoEntryPointStillHasAClasspath() throws IOException {
        FlixBuildSpec spec = FlixBuildSpec.read(projectWith(MANIFEST.replace("\"mainClass\":\"Main\",\n", "")));

        assertNull(spec.mainClass());
        assertEquals(2, spec.runtimeClasspath().size());
    }

    @Test
    public void refusesAnOlderFormat() throws IOException {
        // A v3 manifest records no launch at all. Read as if it did, it would produce a command line
        // with no classpath and no main class -- so this is refused with a message that says to
        // rebuild, rather than half-answered.
        expectFailure(MANIFEST.replace("\"formatVersion\":4", "\"formatVersion\":3"), "format 3");
    }

    @Test
    public void refusesAManifestWithNoLaunchSpec() throws IOException {
        expectFailure(MANIFEST.replaceAll("(?s)\"launch\":\\{.*?}\n", "\"unused\":0\n"), "no launch spec");
    }

    @Test
    public void refusesAManifestWithNoJava() throws IOException {
        expectFailure(MANIFEST.replace("\"java\":\"/opt/jdk/bin/java\",", ""), "no `java`");
    }

    @Test
    public void saysWhatToDoWhenThereIsNoBuildAtAll() throws IOException {
        Path root = Files.createTempDirectory("flix-spec");
        try {
            FlixBuildSpec.read(root);
            fail("expected a failure naming the missing manifest");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Build the project"));
        }
    }

    @Test
    public void readsPathsThatCarryAnEscape() throws IOException {
        // Windows paths arrive with escaped separators, and a project directory can hold a quote on
        // every platform this runs on. Both would otherwise truncate a classpath entry silently.
        FlixBuildSpec spec = FlixBuildSpec.read(projectWith(MANIFEST.replace(
                "\"/p/build/development/class\"", "\"C:\\\\p\\\\build\\\\class\"")));
        assertEquals("C:\\p\\build\\class", spec.runtimeClasspath().get(0));
    }

    @Test
    public void findsTheLaunchObjectPastABraceInAPath() throws IOException {
        // The launch object is brace-counted, and a path is allowed to contain a brace. Counting one
        // inside a string would end the object early and lose every field after it.
        FlixBuildSpec spec = FlixBuildSpec.read(projectWith(MANIFEST.replace(
                "\"/p/build/development/class\"", "\"/p/{old}/class\"")));
        assertEquals("Main", spec.mainClass());
        assertEquals(List.of("/p/{old}/class", "/p/lib/external/core.jar"), spec.runtimeClasspath());
    }

    private void expectFailure(String manifest, String expectedInMessage) throws IOException {
        try {
            FlixBuildSpec.read(projectWith(manifest));
            fail("expected a failure mentioning " + expectedInMessage);
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(expectedInMessage));
        }
    }
}
