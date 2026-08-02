package dev.wstein.flixplugin;

import com.intellij.openapi.project.Project;

import java.nio.file.Path;

/**
 * Mirrors scripts/flix-fork's jar resolution, so the LSP server this plugin launches picks up the
 * same --Xdebug-capable fork build the script itself uses rather than any globally installed
 * upstream `flix`.
 *
 * <p>The rule lives in {@link FlixJar}, in the platform-free {@code shared} module, because the
 * native run/debug configuration must reach the identical answer and sits in {@code debugger} -- a
 * module that cannot depend on this one. Launching a debug session against a different compiler
 * than the editor was analysed with is the kind of inconsistency that shows up as unexplained
 * behaviour rather than as an error, so the choice is made in exactly one place.
 *
 * <p>All this adds is the project's base path, the only part that needs the IntelliJ Platform.
 */
final class FlixFork {

    private FlixFork() {
    }

    static Path resolveJar(Project project) {
        return FlixJar.resolve(project.getBasePath(), System.getenv(FlixJar.JAR_ENV));
    }
}
