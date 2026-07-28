package dev.wstein.flixplugin;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Which Flix compiler jar this plugin launches.
 *
 * <p>Mirrors {@code scripts/flix-fork}: {@code $FLIX_FORK_JAR} if set, otherwise the most recently
 * modified {@code flix-vendor-*.jar} in the project root. Everything this plugin starts — the
 * language server, and the run/debug configuration — must agree on that choice, or a debug session
 * runs a different compiler than the one the editor was analysed with.
 *
 * <p>Lives in {@code shared}, and takes a base path rather than a {@code Project}, for the same
 * reason {@link FlixLaunchCommand} does: this module carries no IntelliJ Platform dependency, and
 * the callers that need this rule sit in modules that cannot depend on each other — the LSP wiring
 * in {@code backend}, which needs LSP4IJ, and the run configuration in {@code debugger}, which
 * needs the Java plugin. A second copy of the rule would drift, and the failure that produces —
 * two compilers in one session — is invisible until something behaves inconsistently.
 */
public final class FlixJar {

    /** The environment variable that pins the jar, bypassing discovery. */
    public static final String PINNED_JAR_ENV = "FLIX_FORK_JAR";

    private FlixJar() {
    }

    /**
     * The jar to launch.
     *
     * @param basePath the project root to search, or {@code null} if the project has none
     * @param pinnedJar the value of {@link #PINNED_JAR_ENV}, or {@code null}/blank if unset
     * @throws IllegalStateException if no jar can be resolved, with a message naming what to do
     */
    public static @NotNull Path resolve(@Nullable String basePath, @Nullable String pinnedJar) {
        if (pinnedJar != null && !pinnedJar.isBlank()) {
            return Path.of(pinnedJar.trim());
        }
        if (basePath == null) {
            throw new IllegalStateException(
                    "Project has no base path; cannot locate flix-vendor-*.jar. Set "
                            + PINNED_JAR_ENV + " to the compiler jar instead.");
        }
        File[] candidates = new File(basePath).listFiles(
                (dir, name) -> name.startsWith("flix-vendor-") && name.endsWith(".jar"));
        if (candidates == null || candidates.length == 0) {
            throw new IllegalStateException("No flix-vendor-*.jar found in " + basePath
                    + ". Build github.com/wstein/flix-fork and drop the resulting jar there, or set "
                    + PINNED_JAR_ENV + ".");
        }
        // Newest wins: rebuilding the fork drops a new jar beside the old one rather than replacing
        // it, so picking any other would silently keep running a stale compiler.
        return Stream.of(candidates)
                .max(Comparator.comparingLong(File::lastModified))
                .map(File::toPath)
                .orElseThrow();
    }
}
