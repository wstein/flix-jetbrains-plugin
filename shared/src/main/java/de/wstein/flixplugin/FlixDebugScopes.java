package de.wstein.flixplugin;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a name in a paused frame <em>is</em>, in Flix rather than in JVM descriptors.
 *
 * <h2>What the debugger already knows, and what it does not</h2>
 *
 * <p>Under {@code --Xdebug} every parameter and capture is in its method's
 * {@code LocalVariableTable}, so a name, a slot and a range are already there — that is what lets a
 * programmer ask for {@code sep} where the bytecode says {@code clo0}. What is not there is the
 * type: the back end erases, so an {@code Option[String]} and a {@code Result[Int32, Bool]} carry
 * the same descriptor. The value can be fetched and not identified.
 *
 * <p>A {@code --Xdebug} build now writes {@code build/development/debug-scopes.json} beside the
 * build manifest, recording exactly that missing half: <em>(class, method, name) → Flix type</em>.
 * Slots and liveness stay where they were. Two tables that both named slots would be two chances to
 * disagree.
 *
 * <h2>What it does not cover</h2>
 *
 * <p>Parameters and captures only. A {@code let}-bound local is named by the compiler where it is
 * compiled and its slot allocated there, so describing it would mean re-implementing slot allocation
 * outside code generation. A local therefore still reads with its erased type, exactly as everything
 * did before this existed — which is why {@link #typeOf} answers {@code null} rather than guessing.
 *
 * <p>A function's <em>result</em> type is erased even here: {@code Int32 -> String} is recorded as
 * {@code (Int32) -> java.lang.Object}, because the back end had already erased it before the table
 * was derived. Measured, not assumed.
 */
public final class FlixDebugScopes {

    /** Where a {@code --Xdebug} build writes it, beside the build manifest. */
    public static final String SCOPES_PATH = "build/development/debug-scopes.json";

    /** The shape this reader understands. A file that says anything else is ignored. */
    private static final int FORMAT_VERSION = 2;

    private static final Pattern VERSION = Pattern.compile("\"formatVersion\"\\s*:\\s*(\\d+)");

    /**
     * The object holding every class.
     *
     * Matched explicitly so that parsing starts inside it. Without this the key {@code "classes"}
     * is itself a class header -- it is a quoted name followed by an object -- and every class in
     * the file becomes a method of a class by that name.
     */
    private static final Pattern CLASSES = Pattern.compile("\"classes\"\\s*:\\s*\\{");

    /** A class header: a quoted binary name followed by an object. */
    private static final Pattern CLASS_HEADER = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\\{");

    /**
     * A method header: a quoted name followed by the start of its binding list.
     *
     * Only the header, deliberately. A type is Flix source text and contains brackets --
     * {@code Option[String]}, {@code Array[Int32]} -- so a pattern that tried to capture the list up
     * to its closing bracket would stop inside the first type it met. The bindings are found
     * between one header and the next instead, which needs no bracket counting at all.
     */
    private static final Pattern METHOD = Pattern.compile("\"([A-Za-z_$][A-Za-z0-9_$]*)\"\\s*:\\s*\\[");

    private static final Pattern BINDING =
            Pattern.compile("\\{\"name\":\"((?:[^\"\\\\]|\\\\.)*)\",\"type\":\"((?:[^\"\\\\]|\\\\.)*)\"}");

    /** Binary class name, then method name, then variable name, then the Flix type. */
    private final Map<String, Map<String, Map<String, String>>> classes;

    private FlixDebugScopes(Map<String, Map<String, Map<String, String>>> classes) {
        this.classes = classes;
    }

    /** A table that knows nothing, which is what a build without one leaves a caller with. */
    public static @NotNull FlixDebugScopes empty() {
        return new FlixDebugScopes(Collections.emptyMap());
    }

    /**
     * The table of the development build under {@code projectRoot}, or an empty one.
     *
     * <p>Empty covers every ordinary reason there is nothing to read: no build yet, a build made
     * without {@code --Xdebug}, or one made by a compiler that predates the table. None is an error;
     * the caller falls back to the erased type, which is what every debugger had before.
     */
    public static @NotNull FlixDebugScopes read(@NotNull Path projectRoot) {
        Path path = projectRoot.resolve(SCOPES_PATH).normalize();
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
        Matcher classes = CLASSES.matcher(text);
        if (!classes.find(version.end())) {
            return empty();
        }
        return new FlixDebugScopes(parse(text.substring(classes.end())));
    }

    /**
     * The Flix type of {@code name} in {@code method} of {@code className}, or {@code null}.
     *
     * <p>{@code null} is the ordinary answer for a local, for a build with no table, and for a frame
     * in another language. A caller that turned it into a guess would be reporting a type nobody
     * recorded.
     */
    public @Nullable String typeOf(@NotNull String className, @NotNull String method, @NotNull String name) {
        Map<String, Map<String, String>> methods = classes.get(className);
        if (methods == null) {
            return null;
        }
        Map<String, String> bindings = methods.get(method);
        return bindings == null ? null : bindings.get(name);
    }

    /** Whether anything was read at all. */
    public boolean isEmpty() {
        return classes.isEmpty();
    }

    /** How many classes it describes, which is what a log line about it should say. */
    public int size() {
        return classes.size();
    }

    /**
     * Reads the classes out of the {@code classes} object.
     *
     * <p>By pattern rather than with a parser, as {@code FlixBuildSpec} and {@code FlixDebugIndex}
     * read their files and for the same reason: this module carries no JSON dependency, because it
     * is loaded in every process including ones with no IntelliJ Platform at all.
     *
     * <p>The nesting is handled by position rather than by a stack: a class header is followed by
     * its own methods and by nothing else, so each method found after a header belongs to it until
     * the next header. That is a property of the writer's output, and both ends are pinned by tests.
     */
    private static Map<String, Map<String, Map<String, String>>> parse(String body) {
        List<String> names = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        Matcher header = CLASS_HEADER.matcher(body);
        while (header.find()) {
            names.add(unescape(header.group(1)));
            starts.add(header.end());
        }

        Map<String, Map<String, Map<String, String>>> classes = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            int to = i + 1 < names.size() ? starts.get(i + 1) : body.length();
            classes.put(names.get(i), methodsIn(body.substring(starts.get(i), to)));
        }
        return classes;
    }

    private static Map<String, Map<String, String>> methodsIn(String body) {
        List<String> names = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        Matcher method = METHOD.matcher(body);
        while (method.find()) {
            names.add(unescape(method.group(1)));
            starts.add(method.end());
        }

        Map<String, Map<String, String>> methods = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            int to = i + 1 < names.size() ? starts.get(i + 1) : body.length();
            methods.put(names.get(i), bindingsIn(body.substring(starts.get(i), to)));
        }
        return methods;
    }

    private static Map<String, String> bindingsIn(String body) {
        Map<String, String> bindings = new LinkedHashMap<>();
        Matcher binding = BINDING.matcher(body);
        while (binding.find()) {
            bindings.put(unescape(binding.group(1)), unescape(binding.group(2)));
        }
        return bindings;
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
