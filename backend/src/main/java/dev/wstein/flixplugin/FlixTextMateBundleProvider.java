package dev.wstein.flixplugin;

import org.jetbrains.plugins.textmate.api.TextMateBundleProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Registers a bundled, static TextMate grammar for .flix files (see
 * src/main/resources/textmate-bundle/) as a baseline fallback -- the official VS Code extension's
 * highlighting comes entirely from LSP semantic tokens (its package.json declares "grammars": []),
 * which only render once the language server has actually analyzed a file; a file that's slow to
 * process (LSP4IJ's own ReadAction timeout warnings were observed live on a large Java-interop-heavy
 * file) shows no highlighting at all in the meantime without this.
 *
 * com.intellij.textmate.bundleProvider is a real, declarative IntelliJ Platform extension point
 * (org.jetbrains.plugins.textmate.api.TextMateBundleProvider, part of the platform's bundled
 * TextMate support, not LSP4IJ) that lets a plugin register a bundle with zero user action --
 * confirmed by reading the TextMate plugin's own extension point declaration and provider
 * interface source, not assumed.
 */
public class FlixTextMateBundleProvider implements TextMateBundleProvider {

    @Override
    public List<PluginBundle> getBundles() {
        return List.of(new PluginBundle("Flix (bundled fallback grammar)", extractBundle()));
    }

    /** TextMateBundleProvider needs a real filesystem Path, not a classpath resource, so the
     * bundle (package.json + syntaxes/flix.tmLanguage.json) is extracted to a temp directory --
     * the same technique used for FlixDebugAdapter.java's resource in FlixDebugAdapterDescriptor. */
    private static Path extractBundle() {
        try {
            Path dir = Files.createTempDirectory("flix-textmate-bundle");
            dir.toFile().deleteOnExit();
            extractResource("/textmate-bundle/package.json", dir.resolve("package.json"));
            Path syntaxesDir = Files.createDirectories(dir.resolve("syntaxes"));
            syntaxesDir.toFile().deleteOnExit();
            extractResource("/textmate-bundle/syntaxes/flix.tmLanguage.json", syntaxesDir.resolve("flix.tmLanguage.json"));
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void extractResource(String resourcePath, Path target) throws IOException {
        try (InputStream in = FlixTextMateBundleProvider.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException(resourcePath + " missing from plugin resources");
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            target.toFile().deleteOnExit();
        }
    }
}
