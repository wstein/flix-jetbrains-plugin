package dev.wstein.flixplugin;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which generated classes carry code from which {@code .flix} file.
 *
 * <h2>What it is for</h2>
 *
 * <p>A generated class name encodes the symbol it came from and never the file — {@code Def$main},
 * {@code Clo$main$626ZYxrpg1N} — so a breakpoint on a line has no class name to watch for. Without
 * this, the only correct request is one that watches <em>every</em> class the VM prepares and
 * re-checks the source on arrival: one event per class loaded, for every breakpoint.
 *
 * <p>A {@code --Xdebug} build writes this index beside the build manifest
 * ({@code DebugIndex} in the compiler), so the classes can be named exactly.
 *
 * <h2>What it does not decide</h2>
 *
 * <p>Only <em>which classes to watch</em>. Whether a prepared class can host the breakpoint is still
 * a question about the line, and still answered per class by the line query — a class can carry a
 * file's code without carrying the line in question. The index narrows the search; it does not
 * shorten the check.
 *
 * <p>It is deliberately many-to-many in both directions: one file yields many classes, and one class
 * may name several files, its own and every file inlined into it. Neither direction is an account of
 * which source <em>owns</em> a class — code generation is whole-program, and the build manifest
 * refuses that question for the same reason.
 */
public final class FlixDebugIndex {

    /** Where a {@code --Xdebug} build writes it, beside the build manifest. */
    public static final String INDEX_PATH = "build/development/debug-index.json";

    /** The shape this reader understands. A file that says anything else is ignored. */
    private static final int FORMAT_VERSION = 1;

    private static final Pattern VERSION = Pattern.compile("\"formatVersion\"\\s*:\\s*(\\d+)");

    /** One entry: a quoted source, a colon, and a bracketed list of quoted class names. */
    private static final Pattern ENTRY = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\\[([^]]*)]");

    private static final Pattern CLASS_NAME = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final Map<String, List<String>> sources;

    private FlixDebugIndex(Map<String, List<String>> sources) {
        this.sources = sources;
    }

    /** An index that names nothing, which is what a build without one leaves a caller with. */
    public static @NotNull FlixDebugIndex empty() {
        return new FlixDebugIndex(Collections.emptyMap());
    }

    /**
     * The index of the development build under {@code projectRoot}, or an empty one.
     *
     * <p>Empty covers every ordinary reason there is nothing to read: no build yet, a build made
     * without {@code --Xdebug}, or one made by a compiler that predates the index. None of them is
     * an error — the caller watches every class instead, as it did before this existed.
     */
    public static @NotNull FlixDebugIndex read(@NotNull Path projectRoot) {
        Path path = projectRoot.resolve(INDEX_PATH).normalize();
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException absent) {
            return empty();
        }
        Matcher version = VERSION.matcher(text);
        if (!version.find() || Integer.parseInt(version.group(1)) != FORMAT_VERSION) {
            return empty();
        }
        return new FlixDebugIndex(parse(text.substring(version.end())));
    }

    /**
     * The classes that carry code from the source named {@code sourceName}, by base name.
     *
     * <p>By base name because that is what a debugger has: a breakpoint knows the file it is in, and
     * the index keys are the source names the compiler emitted — an absolute path for a file on
     * disk, a bare name for a library source. Comparing on the last segment answers both without
     * needing to know which kind it is.
     *
     * <p>Empty when the file is not in the index, which a caller must not read as "no classes":
     * an index that has never been written says nothing about anything.
     */
    public @NotNull List<String> classesFor(@NotNull String sourceName) {
        String base = baseNameOf(sourceName);
        List<String> matches = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : sources.entrySet()) {
            if (baseNameOf(entry.getKey()).equals(base)) {
                matches.addAll(entry.getValue());
            }
        }
        return matches;
    }

    /** Whether anything was read at all. */
    public boolean isEmpty() {
        return sources.isEmpty();
    }

    /** How many sources it names, which is what a log line about it should say. */
    public int size() {
        return sources.size();
    }

    /**
     * Reads the entries out of the {@code sources} object.
     *
     * <p>By pattern rather than with a parser, as {@code FlixBuildSpec} reads the build manifest and
     * for the same reason: this module carries no JSON dependency, because it is loaded in every
     * process including ones with no IntelliJ Platform at all. The shape is the compiler's own and
     * both ends are pinned by tests.
     */
    private static Map<String, List<String>> parse(String body) {
        Map<String, List<String>> sources = new LinkedHashMap<>();
        Matcher entry = ENTRY.matcher(body);
        while (entry.find()) {
            List<String> classes = new ArrayList<>();
            Matcher name = CLASS_NAME.matcher(entry.group(2));
            while (name.find()) {
                classes.add(unescape(name.group(1)));
            }
            sources.put(unescape(entry.group(1)), classes);
        }
        return sources;
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String baseNameOf(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
