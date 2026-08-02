package dev.wstein.flixplugin;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * Which Flix compiler jar this plugin launches.
 *
 * <p>{@code $FLIX_JAR} if set, otherwise {@code flix.jar} in the project root. Everything this plugin starts — the
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
    public static final String JAR_ENV = "FLIX_JAR";

    private FlixJar() {
    }

    /**
     * The jar to launch.
     *
     * @param basePath the project root to search, or {@code null} if the project has none
     * @param pinnedJar the value of {@link #JAR_ENV}, or {@code null}/blank if unset
     * @throws IllegalStateException if no jar can be resolved, with a message naming what to do
     */
    public static @NotNull Path resolve(@Nullable String basePath, @Nullable String pinnedJar) {
        if (pinnedJar != null && !pinnedJar.isBlank()) {
            return Path.of(pinnedJar.trim());
        }
        if (basePath == null) {
            throw new IllegalStateException(
                    "Project has no base path; cannot locate flix.jar. Set "
                            + JAR_ENV + " to the compiler jar instead.");
        }
        Path jar = Path.of(basePath, "flix.jar");
        if (!jar.toFile().isFile()) {
            throw new IllegalStateException("No flix.jar found in " + basePath
                    + ". Place the Flix compiler jar at the project root, or set "
                    + JAR_ENV + ".");
        }
        return jar;
    }
}
