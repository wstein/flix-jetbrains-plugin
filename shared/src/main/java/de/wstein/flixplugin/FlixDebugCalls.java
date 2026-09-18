package de.wstein.flixplugin;

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

/** Compiler-authored source call sites and the JVM definitions they invoke. */
public final class FlixDebugCalls {

    public static final String CALLS_PATH = "build/development/debug-calls.json";
    private static final int FORMAT_VERSION = 2;
    private static final String DEFAULT_METHOD = "staticApply";
    private static final Pattern VERSION = Pattern.compile("\"formatVersion\"\\s*:\\s*(\\d+)");
    private static final Pattern SOURCES = Pattern.compile("\"sources\"\\s*:\\s*\\{");
    private static final Pattern SOURCE = Pattern.compile(
            "\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\\[\\s*(?=\\{)");
    private static final Pattern CALL = Pattern.compile(
            "\\{\\s*\"range\"\\s*:\\s*\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*]\\s*," +
            "\\s*\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*," +
            "\\s*\"target\"\\s*:\\s*\\{\\s*\"className\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"" +
            "(?:\\s*,\\s*\"methodName\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\")?\\s*}\\s*}");

    /** Source identity, then one-based source line, then calls intersecting that line. */
    private final Map<String, Map<Integer, List<Call>>> sources;

    private FlixDebugCalls(Map<String, Map<Integer, List<Call>>> sources) {
        this.sources = sources;
    }

    public static @NotNull FlixDebugCalls empty() {
        return new FlixDebugCalls(Collections.emptyMap());
    }

    /** Reads the current debug build's call provenance; absent or malformed data fails closed. */
    public static @NotNull FlixDebugCalls read(@NotNull Path projectRoot) {
        final String text;
        try {
            text = Files.readString(projectRoot.resolve(CALLS_PATH).normalize(), StandardCharsets.UTF_8);
        } catch (IOException absent) {
            return empty();
        }
        try {
            Matcher version = VERSION.matcher(text);
            if (!version.find() || Integer.parseInt(version.group(1)) != FORMAT_VERSION) return empty();
            Matcher sources = SOURCES.matcher(text);
            if (!sources.find(version.end())) return empty();
            return new FlixDebugCalls(parse(text.substring(sources.end())));
        } catch (IllegalArgumentException malformed) {
            return empty();
        }
    }

    /** Calls whose source span intersects the one-based source line. */
    public @NotNull List<Call> callsOn(@NotNull String sourceName, int line) {
        Map<Integer, List<Call>> source = sources.get(sourceName);
        if (source == null) {
            String base = baseNameOf(sourceName);
            List<String> recorded = sources.keySet().stream()
                    .filter(candidate -> baseNameOf(candidate).equals(base)).toList();
            if (recorded.size() != 1) return List.of();
            source = sources.get(recorded.getFirst());
        }
        return source.getOrDefault(line, List.of());
    }

    public boolean isEmpty() {
        return sources.isEmpty();
    }

    /** Parses the v2 source tree and materializes its line index once. */
    private static Map<String, Map<Integer, List<Call>>> parse(String text) {
        record Header(String source, int bodyStart, int headerStart) {}
        List<Header> headers = new ArrayList<>();
        Matcher source = SOURCE.matcher(text);
        while (source.find()) {
            headers.add(new Header(unescape(source.group(1)), source.end(), source.start()));
        }
        if (headers.isEmpty()) return Map.of();

        Map<String, Map<Integer, List<Call>>> indexed = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            Header header = headers.get(i);
            int end = i + 1 < headers.size() ? headers.get(i + 1).headerStart() : text.length();
            Matcher call = CALL.matcher(text.substring(header.bodyStart(), end));
            boolean found = false;
            while (call.find()) {
                found = true;
                int startLine = positive(call.group(1));
                int startCol = positive(call.group(2));
                int endLine = positive(call.group(3));
                int endCol = positive(call.group(4));
                if (endLine < startLine || (endLine == startLine && endCol < startCol)) {
                    throw new IllegalArgumentException("Reversed source range");
                }
                Call parsed = new Call(
                        header.source(), startLine, startCol, endLine, endCol,
                        unescape(call.group(5)), unescape(call.group(6)),
                        call.group(7) == null ? DEFAULT_METHOD : unescape(call.group(7)));
                Map<Integer, List<Call>> lines = indexed.computeIfAbsent(
                        header.source(), ignored -> new LinkedHashMap<>());
                for (int line = startLine; line <= endLine; line++) {
                    lines.computeIfAbsent(line, ignored -> new ArrayList<>()).add(parsed);
                }
            }
            if (!found) throw new IllegalArgumentException("Source has no valid calls");
        }

        Map<String, Map<Integer, List<Call>>> frozen = new LinkedHashMap<>();
        indexed.forEach((name, lines) -> {
            Map<Integer, List<Call>> frozenLines = new LinkedHashMap<>();
            lines.forEach((line, calls) -> frozenLines.put(line, List.copyOf(calls)));
            frozen.put(name, Map.copyOf(frozenLines));
        });
        return Map.copyOf(frozen);
    }

    private static int positive(String value) {
        int result = Integer.parseInt(value);
        if (result <= 0) throw new IllegalArgumentException("Source coordinates are one-based");
        return result;
    }

    private static String baseNameOf(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String unescape(String s) {
        StringBuilder result = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') {
                if (c < 0x20) throw new IllegalArgumentException("Unescaped JSON control character");
                result.append(c);
                continue;
            }
            if (++i == s.length()) throw new IllegalArgumentException("Incomplete JSON escape");
            switch (s.charAt(i)) {
                case '"', '\\', '/' -> result.append(s.charAt(i));
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'u' -> {
                    if (i + 4 >= s.length()) throw new IllegalArgumentException("Incomplete Unicode escape");
                    result.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> throw new IllegalArgumentException("Unknown JSON escape");
            }
        }
        return result.toString();
    }

    public record Call(
            @NotNull String source,
            int startLine,
            int startCol,
            int endLine,
            int endCol,
            @NotNull String label,
            @NotNull String className,
            @NotNull String methodName) {
    }
}
