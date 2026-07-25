package dev.wstein.flixplugin;

import com.intellij.openapi.project.Project;

import java.io.File;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Mirrors scripts/flix-fork's jar resolution: $FLIX_FORK_JAR if set, otherwise the most recently
 * modified flix-vendor-*.jar in the open project's root -- so the LSP and DAP servers this plugin
 * launches pick up the same --Xdebug-capable fork build scripts/flix-fork itself uses, rather than
 * any globally installed upstream `flix`.
 */
final class FlixFork {

    private FlixFork() {
    }

    static Path resolveJar(Project project) {
        String pinned = System.getenv("FLIX_FORK_JAR");
        if (pinned != null && !pinned.isBlank()) {
            return Path.of(pinned);
        }
        String basePath = project.getBasePath();
        if (basePath == null) {
            throw new IllegalStateException("Project has no base path; cannot locate flix-vendor-*.jar");
        }
        File[] candidates = new File(basePath)
                .listFiles((dir, name) -> name.startsWith("flix-vendor-") && name.endsWith(".jar"));
        if (candidates == null || candidates.length == 0) {
            throw new IllegalStateException("No flix-vendor-*.jar found in " + basePath
                    + ". Build github.com/wstein/flix-fork and drop the resulting jar there, or set FLIX_FORK_JAR.");
        }
        return Stream.of(candidates)
                .max(Comparator.comparingLong(File::lastModified))
                .map(File::toPath)
                .orElseThrow();
    }
}
