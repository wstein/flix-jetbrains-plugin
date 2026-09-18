package de.wstein.flixplugin;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compiler-authored source call sites and the JVM definitions they invoke. */
public final class FlixDebugCalls {

    public static final String CALLS_PATH = "build/development/debug-calls.json";
    private static final int FORMAT_VERSION = 1;
    private static final Pattern VERSION = Pattern.compile("\"formatVersion\"\\s*:\\s*(\\d+)");
    private static final Pattern CALL = Pattern.compile(
            "\\{\\s*\"source\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*," +
            "\\s*\"startLine\"\\s*:\\s*(\\d+)\\s*," +
            "\\s*\"startCol\"\\s*:\\s*(\\d+)\\s*," +
            "\\s*\"endLine\"\\s*:\\s*(\\d+)\\s*," +
            "\\s*\"endCol\"\\s*:\\s*(\\d+)\\s*," +
            "\\s*\"label\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*," +
            "\\s*\"className\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*," +
            "\\s*\"methodName\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*}");

    private final List<Call> calls;

    private FlixDebugCalls(List<Call> calls) {
        this.calls = List.copyOf(calls);
    }

    public static @NotNull FlixDebugCalls empty() {
        return new FlixDebugCalls(Collections.emptyList());
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
            List<Call> parsed = new ArrayList<>();
            Matcher call = CALL.matcher(text.substring(version.end()));
            while (call.find()) {
                parsed.add(new Call(
                        unescape(call.group(1)),
                        Integer.parseInt(call.group(2)),
                        Integer.parseInt(call.group(3)),
                        Integer.parseInt(call.group(4)),
                        Integer.parseInt(call.group(5)),
                        unescape(call.group(6)),
                        unescape(call.group(7)),
                        unescape(call.group(8))));
            }
            return new FlixDebugCalls(parsed);
        } catch (IllegalArgumentException malformed) {
            return empty();
        }
    }

    /** Calls whose source span intersects the one-based source line. */
    public @NotNull List<Call> callsOn(@NotNull String sourceName, int line) {
        List<Call> exact = calls.stream().filter(call -> call.source().equals(sourceName)).toList();
        List<Call> candidates = exact;
        if (candidates.isEmpty()) {
            String base = baseNameOf(sourceName);
            List<String> recorded = calls.stream().map(Call::source)
                    .filter(source -> baseNameOf(source).equals(base)).distinct().toList();
            if (recorded.size() != 1) return List.of();
            candidates = calls.stream().filter(call -> call.source().equals(recorded.getFirst())).toList();
        }
        List<Call> matches = new ArrayList<>();
        for (Call call : candidates) {
            if (call.startLine() <= line && line <= call.endLine()) {
                matches.add(call);
            }
        }
        return List.copyOf(matches);
    }

    public boolean isEmpty() {
        return calls.isEmpty();
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
