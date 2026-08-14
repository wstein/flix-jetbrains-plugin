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

    /** The variable naming a JDK for the compiler, which flixw defines and this honours. */
    public static final String JAVA_HOME_ENV = "FLIX_JAVA_HOME";

    /** The JDK on {@code PATH}, for a project that names none. */
    public static final String DEFAULT_JAVA = "java";

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
        // `.envrc` before the ambient variable, because that is the order both direnv and this
        // plugin already use: the more specific setting wins. In a terminal direnv would have
        // overwritten the inherited value on entering the directory, so deferring to the ambient
        // one here would make the IDE the only place the project's own choice loses.
        String fromEnvrc = basePath == null ? null : FlixEnvrc.valueOf(Path.of(basePath), JAR_ENV);
        if (fromEnvrc != null) {
            Path jar = Path.of(fromEnvrc);
            // Checked, unlike `pinnedJar` below, and the asymmetry is deliberate. `$FLIX_JAR` is
            // set by whoever is running this IDE now, so a wrong value is theirs to see and undo.
            // An `.envrc` is a committed file that is read silently and outranks everything --
            // including a flixw jar that was verified against a digest -- so a stale entry there
            // shadows a working compiler with nothing pointing at the cause. Failing here names
            // the file; falling through would launch a *different* compiler than the project asked
            // for, which is the one outcome this class exists to prevent.
            if (!jar.toFile().isFile()) {
                throw new IllegalStateException(FlixEnvrc.ENVRC + " sets " + JAR_ENV + " to "
                        + fromEnvrc + ", which is not a file. Correct it, or remove it to fall back "
                        + "to the project's own compiler.");
            }
            return jar;
        }
        if (pinnedJar != null && !pinnedJar.isBlank()) {
            return Path.of(pinnedJar.trim());
        }
        if (basePath == null) {
            throw new IllegalStateException(
                    "Project has no base path; cannot locate flix.jar. Set "
                            + JAR_ENV + " to the compiler jar instead.");
        }
        Path root = Path.of(basePath);

        // A wrapper has already pinned a compiler and verified it against a digest, which is a
        // better answer than a loose jar in the project root -- and the one `./flixw run` in a
        // terminal would use, so the editor and the command line agree about what they analysed.
        FlixwProject.Installation flixw = FlixwProject.resolve(root);
        if (flixw != null && flixw.hasJar()) {
            return flixw.jar();
        }

        Path jar = root.resolve("flix.jar");
        if (!jar.toFile().isFile()) {
            // A wrapper with nothing pinned is the one case where the fix is a command rather than
            // a file, so it is worth naming: `flixw install` writes the shim but leaves the lock
            // for `pin`, and until then the project has a wrapper and no compiler.
            if (FlixwProject.isFlixwProject(root)) {
                throw new IllegalStateException("This project uses flixw, but no compiler is pinned yet. "
                        + "Run `./flixw pin <version>` in " + basePath + ", or set " + JAR_ENV + ".");
            }
            throw new IllegalStateException("No flix.jar found in " + basePath
                    + ". Place the Flix compiler jar at the project root, or set "
                    + JAR_ENV + ".");
        }
        return jar;
    }

    /**
     * The {@code java} to launch the compiler with, for a project that pins one.
     *
     * <p>{@code "java"} otherwise, which is what every launch used before flixw was understood: the
     * one on {@code PATH}, chosen by whatever started the IDE.
     *
     * <p>This is the second half of respecting a wrapper's decisions, and it is not cosmetic. flixw
     * pins a JDK for the same reason it pins a compiler, and a project pinned to 21 analysed on
     * whatever {@code PATH} offers is a project whose editor and terminal disagree about the
     * runtime -- which surfaces as a compiler that behaves differently in one of them, with nothing
     * pointing at the JDK as the cause.
     *
     * <p>{@link #JAR_ENV} does <em>not</em> suppress this. Overriding which compiler to run says
     * nothing about which JDK to run it on, and flixw honours the same variable while keeping its
     * own JDK, so this matches what the wrapper itself would do.
     *
     * <p>The order is flixw's own ({@code flixw.java:974-996}) with one step removed:
     * {@link #JAVA_HOME_ENV}, then the JDK flixw installed, then {@code PATH}. What is missing
     * between the first two is "the JVM flixw is running on", and it is missing because it cannot
     * be answered from here — the JVM <em>this</em> runs on is the IDE's, not the terminal's. See
     * {@link FlixwProject#installedJdk}.
     */
    public static @NotNull String javaExecutable(@Nullable String basePath) {
        if (basePath == null) {
            return DEFAULT_JAVA;
        }
        Path root = Path.of(basePath);

        // The variable first, as flixw reads it first: an explicit JDK is an explicit JDK whether
        // or not the project also has a wrapper. `.envrc` before the ambient value, for the reason
        // `resolve` gives above.
        String javaHome = FlixEnvrc.valueOf(root, JAVA_HOME_ENV);
        if (javaHome == null) {
            javaHome = System.getenv(JAVA_HOME_ENV);
        }
        if (javaHome != null && !javaHome.isBlank()) {
            Path java = Path.of(javaHome.trim(), "bin", DEFAULT_JAVA);
            if (java.toFile().isFile()) {
                return java.toString();
            }
        }

        // Then the JDK the wrapper downloaded and recorded, which is a deliberate act by the
        // project and a better answer than whatever `PATH` happens to offer the IDE.
        FlixwProject.Installation flixw = FlixwProject.resolve(root);
        if (flixw != null && flixw.java() != null) {
            return flixw.java().toString();
        }
        return DEFAULT_JAVA;
    }
}
