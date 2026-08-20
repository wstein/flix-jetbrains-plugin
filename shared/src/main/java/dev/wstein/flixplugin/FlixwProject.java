package dev.wstein.flixplugin;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a <a href="https://github.com/wstein/flixw">flixw</a> project has already decided.
 *
 * <p>flixw pins a compiler version in the project, verifies the downloaded jar against a digest
 * recorded in {@code .flixw/lock.toml}, and keeps the verified jars in a shared cache. A project
 * with a wrapper has therefore already answered the question this plugin would otherwise ask, and
 * answered it more precisely: which compiler, and which JDK to run it on.
 *
 * <h2>Why the lock is read rather than the wrapper run</h2>
 *
 * <p>Because running it is arbitrary code execution on project open. {@link FlixJar#resolve} is
 * reached from the language server's startup, so merely opening a cloned repository would execute
 * the {@code flixw} script that repository ships — with the project's own {@code .envrc} merged into
 * its environment. direnv requires {@code direnv allow} before it will evaluate a file for exactly
 * this reason, and {@link FlixEnvrc} already declines to start a shell on the same grounds; running
 * the wrapper would have taken with one hand what that class refuses with the other.
 *
 * <p>An earlier revision did run {@code ./flixw info} and parse its report. Three further defects
 * came with it and are gone rather than fixed: the output was drained to EOF <em>before</em>
 * {@code waitFor(timeout)}, so the timeout could never bound a wrapper that hung; the subprocess ran
 * inside a {@code ConcurrentHashMap.computeIfAbsent} mapping function, which holds a bin lock for
 * its duration; and the report's aligned columns were parsed by matching two literal spaces.
 *
 * <h2>What that costs, stated exactly</h2>
 *
 * <p>The layout below is flixw's, and this is a second implementation of it. That is a real cost —
 * a change to the cache layout breaks this silently — and it is bounded two ways. Every rule here is
 * a transcription of one <em>named</em> function in the wrapper's own source, and each is a pure
 * function of the lock plus the environment.
 *
 * <p>The second bound is {@code FlixwTranscriptionTest}, which compares those functions' bodies
 * against a flixw checkout and fails when one changes. It replaces the line-number citations this
 * class used to carry, every one of which was stale within about eight days: the behaviour still
 * matched, by luck, while the thing that was supposed to prove it had quietly expired.
 *
 * <p>The JDK is the exception, and it is not derivable. flixw picks one by search
 * ({@code javaExe}): an environment variable, then <em>the JVM flixw is running on</em>,
 * then its own installed JDK, then known system installations. The second of those cannot be
 * reproduced here by construction — the JVM this code runs on is the IDE's, not the terminal's — so
 * this deliberately implements a narrower rule and says so. See {@link #installedJdk}.
 *
 * <h2>Why this plugin does not simply run {@code ./flixw} to launch, either</h2>
 *
 * <p>Because of the debugger. flixw refuses {@code -agentlib:*} in {@code FLIX_JVM_OPTS} unless
 * {@code FLIXW_UNSAFE_JVM_OPTS=1} is set, which is a sound rule for a tool whose job is
 * reproducibility — and it means every debug session would have to switch that safety off.
 * ADR 0002 makes IntelliJ's own debugger the sole owner of JDWP, and it attaches the agent before
 * {@code -jar}. Resolving the jar and launching it directly keeps both facts intact: flixw decides
 * <em>what</em> runs, this plugin decides <em>how</em> it is started.
 *
 * <p>Lives in {@code shared} beside {@link FlixJar} because the language server, the task runner
 * and the debug configuration must all reach the same compiler, and they sit in modules that
 * cannot depend on each other.
 */
public final class FlixwProject {

    /** The wrapper script, as {@code flixw install} writes it. */
    static final String WRAPPER_POSIX = "flixw";

    /** The same wrapper on Windows, where the shim is a batch file. */
    static final String WRAPPER_WINDOWS = "flixw.cmd";

    /** The lock. Its presence is what distinguishes an installed wrapper from a stray script. */
    static final String LOCK = ".flixw/lock.toml";

    /** The variable that relocates the cache, which the wrapper honours and so must this. */
    static final String CACHE_HOME_ENV = "FLIX_CACHE_HOME";

    /**
     * A digest, exactly as flixw writes one.
     *
     * <p>Enforced rather than trusted because the digest is <em>interpolated into a file name</em>
     * that is then opened: a lock is a committed file, so a hostile one naming
     * {@code ../../../../etc/passwd} would otherwise be a path traversal out of the cache. flixw
     * writes {@code String.format("%064x", ...)}, so anything else is not a lock this understands.
     * Checked against the wrapper's own source by {@code FlixwTranscriptionTest}.
     */
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

    /**
     * A version, restricted to what can safely become part of a file name.
     *
     * <p>Same reasoning as {@link #DIGEST}: {@code canonical(version)} is interpolated too, and a
     * version carrying a path separator would escape the cache directory.
     */
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z._+-]+");

    /** A TOML table header, {@code [compiler]}. */
    private static final Pattern TABLE = Pattern.compile("^\\s*\\[([^\\]]+)]\\s*$");

    /** A quoted scalar, {@code key = "value"} — the only shape any key this reads is written in. */
    private static final Pattern ENTRY = Pattern.compile("^\\s*([A-Za-z0-9_-]+)\\s*=\\s*\"([^\"]*)\"\\s*(?:#.*)?$");

    private FlixwProject() {
    }

    /**
     * What a wrapper pinned: which jar, and which {@code java} to run it with.
     *
     * @param jar  the verified compiler jar, or {@code null} when nothing is pinned yet
     * @param java the JDK flixw installed, or {@code null} when this cannot tell
     */
    public record Installation(@Nullable Path jar, @Nullable Path java) {

        /** Whether this names a compiler that can actually be launched. */
        public boolean hasJar() {
            return jar != null;
        }
    }

    /** Whether {@code root} is a project with a flixw wrapper installed. */
    public static boolean isFlixwProject(@Nullable Path root) {
        return root != null && wrapperIn(root) != null && Files.isRegularFile(root.resolve(LOCK));
    }

    /**
     * The wrapper script in {@code root}, or {@code null} if there is none.
     *
     * <p>Both names are looked for on every platform rather than only the one this OS uses: the
     * pair is committed together, and a project checked out on Windows still has the POSIX shim
     * beside the batch file.
     */
    static @Nullable Path wrapperIn(@NotNull Path root) {
        for (String name : List.of(WRAPPER_POSIX, WRAPPER_WINDOWS)) {
            Path candidate = root.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * What the wrapper in {@code root} pinned, or {@code null} if {@code root} has no wrapper.
     *
     * <p>Never throws. Every failure — no wrapper, an unreadable lock, a digest that is not one, a
     * jar the cache no longer holds — answers "nothing pinned", because the caller already has a
     * correct behaviour for that and a project that merely has a wrapper must not become a project
     * that cannot be opened.
     *
     * <p>Not cached. The previous revision memoised this behind a key built from file modification
     * times, which leaked an entry per {@code ./flixw pin} for the life of the IDE and needed an
     * {@code invalidate()} that only tests called. What it was protecting was a subprocess; reading
     * a two-hundred-byte file needs no protection, and recomputing means a re-pin in a terminal is
     * picked up on the next question rather than whenever a stamp happens to change.
     */
    public static @Nullable Installation resolve(@Nullable Path root) {
        if (!isFlixwProject(root)) {
            return null;
        }
        Path cache = cacheHome(root);
        Lock lock = readLock(root.resolve(LOCK));
        return new Installation(lock == null ? null : existingJar(cache, lock), installedJdk(cache));
    }

    /**
     * Where flixw keeps what it has verified.
     *
     * <p>Transcribed from the wrapper's {@code cacheHome()}, whose body
     * {@code FlixwTranscriptionTest} compares against this one. The {@code .envrc} is consulted before the
     * ambient environment for the same reason {@link FlixJar#resolve} does it: in a terminal direnv
     * would have overwritten the inherited value on entering the directory, so preferring the
     * ambient one here would make the IDE the only place the project's own choice loses.
     */
    static @NotNull Path cacheHome(@NotNull Path root) {
        String override = FlixEnvrc.valueOf(root, CACHE_HOME_ENV);
        if (override == null) {
            override = System.getenv(CACHE_HOME_ENV);
        }
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim()).toAbsolutePath();
        }
        String home = System.getProperty("user.home", "");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            String local = System.getenv("LOCALAPPDATA");
            return Path.of(local != null && !local.isBlank() ? local : home).resolve("flixw");
        }
        if (os.contains("mac")) {
            return Path.of(home, "Library", "Caches", "flixw");
        }
        String xdg = System.getenv("XDG_CACHE_HOME");
        return (xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(home, ".cache")).resolve("flixw");
    }

    /**
     * The jar {@code lock} names, if the cache still holds it.
     *
     * <p>The name is built as the wrapper's {@code compilerPath(Lock)} builds it. Checking the file exists is not
     * belt-and-braces: a cache pruned behind flixw's back leaves a lock that still names a jar, and
     * the alternative to noticing here is {@code java -jar} failing with a path.
     */
    static @Nullable Path existingJar(@NotNull Path cache, @NotNull Lock lock) {
        Path jar = cache.resolve("compilers")
                .resolve("flix-" + canonical(lock.version()) + "-" + lock.sha256() + ".jar");
        return Files.isRegularFile(jar) ? jar : null;
    }

    /**
     * The version with its build metadata dropped, as the wrapper's {@code canonical(String)} does it.
     *
     * <p>A fork pins {@code 0.75.2+fork.wstein.260813.1}; the jar beside it is
     * {@code flix-0.75.2-<digest>.jar}. The metadata identifies the build, and the digest already
     * does that in the file name, so flixw does not repeat it.
     */
    static @NotNull String canonical(@NotNull String version) {
        int plus = version.indexOf('+');
        return plus < 0 ? version : version.substring(0, plus);
    }

    /**
     * The {@code java} flixw installed for itself, if that is still what the marker names.
     *
     * <p>This is deliberately <em>narrower</em> than what flixw would answer. Its own order
     * is: {@code FLIX_JAVA_HOME}/{@code JAVA_HOME}, then the JVM it is
     * running on, then this marker, then known system installations. The second step is the one
     * that cannot be transcribed — flixw running in a terminal picks that terminal's JVM, and the
     * JVM this code runs on is the IDE's — so reproducing the order would produce a confidently
     * wrong answer rather than an absent one. {@link FlixJar#javaExecutable} therefore reads the
     * environment variable itself and falls back to {@code java} on {@code PATH}, which is what
     * every launch did before any of this existed.
     *
     * <p>The containment check is flixw's own, and is kept because the
     * consequence is kept: this path is about to be executed. A lexical prefix test is not
     * containment — a symlink under {@code jdks/} can point anywhere — so both sides are resolved
     * before they are compared.
     */
    static @Nullable Path installedJdk(@NotNull Path cache) {
        Path marker = cache.resolve("jdks").resolve("default");
        try {
            if (!Files.isRegularFile(marker)) {
                return null;
            }
            Path exe = Path.of(Files.readString(marker, StandardCharsets.UTF_8).strip())
                    .toAbsolutePath().normalize();
            Path jdks = cache.resolve("jdks").toAbsolutePath().normalize();
            if (!exe.startsWith(jdks) || !Files.isRegularFile(exe)) {
                return null;
            }
            return exe.toRealPath().startsWith(jdks.toRealPath()) ? exe : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The compiler a lock pins.
     *
     * @param version the pinned version, possibly carrying build metadata
     * @param sha256  the digest flixw verified the download against
     */
    record Lock(@NotNull String version, @NotNull String sha256) {
    }

    /**
     * The {@code [compiler]} table of a lock, or {@code null} if the file is not one.
     *
     * <p>A deliberate subset of TOML, in the same spirit as {@link FlixEnvrc}: the two keys read
     * here are quoted scalars in a single table, because that is what {@code ./flixw pin} writes
     * and a lock is generated rather than hand-edited — its own
     * header says so. Anything else is not understood and answers "nothing pinned" rather than
     * guessing. Both values are validated before they can become part of a file name.
     */
    static @Nullable Lock readLock(@NotNull Path lockFile) {
        List<String> lines;
        try {
            lines = Files.readAllLines(lockFile, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
        String table = "";
        String version = null;
        String sha256 = null;
        for (String line : lines) {
            Matcher header = TABLE.matcher(line);
            if (header.matches()) {
                table = header.group(1).trim();
                continue;
            }
            if (!table.equals("compiler")) {
                continue;
            }
            Matcher entry = ENTRY.matcher(line);
            if (!entry.matches()) {
                continue;
            }
            switch (entry.group(1)) {
                case "version" -> version = entry.group(2);
                case "sha256" -> sha256 = entry.group(2);
                default -> {
                    // `repo` and `url` say where the jar came from, which the cache no longer
                    // needs: the digest identifies it, and the download already happened.
                }
            }
        }
        if (version == null || sha256 == null
                || !VERSION.matcher(version).matches() || !DIGEST.matcher(sha256).matches()) {
            return null;
        }
        return new Lock(version, sha256);
    }
}
