package dev.wstein.flixplugin;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The variables a project's {@code .envrc} exports.
 *
 * <h2>Why this plugin reads a direnv file at all</h2>
 *
 * <p>Because the IDE is not started from the shell that has them. direnv exports into an
 * interactive shell, so {@code ./flixw run} in a terminal sees {@code FLIX_JAR} while an
 * editor launched from a Dock icon does not — and the two then run different compilers with
 * nothing saying so. flixw's own {@code .envrc.example} names this exact gap:
 *
 * <blockquote>direnv exports these into your shell before {@code ./flixw} ever starts, which is
 * also why it reaches a terminal and not an editor-spawned {@code flixw lsp}.</blockquote>
 *
 * <p>It is a gap flixw cannot close from its side. This is the side it can be closed from.
 *
 * <h2>Why it is parsed and never executed</h2>
 *
 * <p>An {@code .envrc} is a bash script, and direnv requires an explicit {@code direnv allow}
 * precisely because evaluating one runs whatever it contains. Opening a project in an editor is
 * not that consent, so nothing here starts a shell. What is understood is the subset the file is
 * written in — {@code export NAME=VALUE}, with the variable references direnv would have expanded
 * — and every other line is ignored. The worst a hostile file can do is name a path that does not
 * resolve.
 *
 * <p>The consequence is worth stating: a value produced by a command substitution or a conditional
 * is not seen. That is a deliberate floor, not an oversight — see {@link #parse}.
 */
public final class FlixEnvrc {

    /** The file direnv reads, and the one this understands a subset of. */
    static final String ENVRC = ".envrc";

    /** {@code export NAME=VALUE}, with {@code export} optional as direnv also allows. */
    private static final Pattern EXPORT = Pattern.compile("^\\s*(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)$");

    /** {@code source_env_if_exists <file>} -- direnv's own include, and the one the template shows. */
    private static final Pattern SOURCE = Pattern.compile("^\\s*source_env(?:_if_exists)?\\s+(\\S+)\\s*$");

    /** {@code $NAME} and {@code ${NAME}}, which direnv would have expanded before exporting. */
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}|\\$([A-Za-z_][A-Za-z0-9_]*)");

    /**
     * Words that open a block whose body may or may not run, and the words that close one.
     *
     * <p>Counted as occurrences rather than matched at the start of a line, so that a block opened
     * and closed on one line — {@code if [ -f x ]; then export A=1; fi} — nets out instead of
     * suppressing the whole rest of the file.
     *
     * <p>{@code elif} is not an opener and does not need to be: it has no word boundary before its
     * {@code if}, so {@code \bif\b} does not see it, and the {@code if} that began the chain is
     * already counted.
     */
    private static final Pattern BLOCK_OPEN = Pattern.compile("\\b(?:if|for|while|until|case)\\b");

    /** @see #BLOCK_OPEN */
    private static final Pattern BLOCK_CLOSE = Pattern.compile("\\b(?:fi|done|esac)\\b");

    /** How many includes deep to follow, so a file that includes itself cannot loop. */
    private static final int MAX_DEPTH = 4;

    private FlixEnvrc() {
    }

    /**
     * What {@code root}'s {@code .envrc} exports, in file order. Empty when there is none.
     *
     * <p>Later assignments win, and an assignment may refer to an earlier one, which is what the
     * file's own semantics are.
     */
    public static @NotNull Map<String, String> read(@Nullable Path root) {
        if (root == null) {
            return Map.of();
        }
        Map<String, String> exported = new LinkedHashMap<>();
        parse(root, root.resolve(ENVRC), exported, 0);
        return exported;
    }

    /**
     * The value {@code root}'s {@code .envrc} gives {@code name}, or {@code null}.
     *
     * <p>Blank counts as unset: the template's own idiom for turning something off is an empty
     * value, and an empty path is not a compiler.
     */
    public static @Nullable String valueOf(@Nullable Path root, @NotNull String name) {
        String value = read(root).get(name);
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Reads {@code file} into {@code exported}.
     *
     * <p>A line that is not an assignment or an include is skipped in silence, which covers
     * comments, blanks, and everything this deliberately does not implement: command substitution,
     * conditionals, direnv's other builtins. Skipping is the right answer for all of them — the
     * alternative to not knowing a value is not knowing it *and* refusing to open the project.
     */
    private static void parse(Path root, Path file, Map<String, String> exported, int depth) {
        if (depth > MAX_DEPTH || !Files.isRegularFile(file)) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return;
        }
        int conditional = 0;
        for (String line : lines) {
            if (line.isBlank() || line.trim().startsWith("#")) {
                continue;
            }
            // Read before the line's own delta is applied, so the `if` line itself is still at the
            // top level and only the body it opens is suppressed.
            boolean reachable = conditional == 0;
            conditional = Math.max(0, conditional + count(BLOCK_OPEN, line) - count(BLOCK_CLOSE, line));
            if (!reachable) {
                continue;
            }
            Matcher include = SOURCE.matcher(line);
            if (include.matches()) {
                parse(root, root.resolve(readValue(include.group(1)).value()), exported, depth + 1);
                continue;
            }
            Matcher export = EXPORT.matcher(line);
            if (!export.matches()) {
                continue;
            }
            Unquoted raw = readValue(export.group(2));
            String value = raw.literal() ? raw.value() : expand(raw.value(), root, exported);
            exported.put(export.group(1), value);
        }
    }

    private static int count(Pattern pattern, String line) {
        return (int) pattern.matcher(line).results().count();
    }

    /**
     * Drops an end-of-line comment from an unquoted value.
     *
     * <p>Only when the {@code #} is preceded by whitespace, because a {@code #} inside a value is
     * an ordinary character -- a URL fragment, or a path someone chose badly.
     */
    private static String stripTrailingComment(String value) {
        Matcher comment = Pattern.compile("\\s+#.*$").matcher(value);
        return comment.find() ? value.substring(0, comment.start()).trim() : value;
    }

    /**
     * The right-hand side of an assignment, without its quotes and without any trailing comment.
     *
     * <p>Single quotes suppress expansion and double quotes do not, which is bash's rule and the
     * one the file is written against.
     *
     * <p>The quote is found <em>before</em> the comment is stripped, and that order is the whole
     * point: a {@code #} inside quotes is an ordinary character. Stripping first turned
     * {@code export FLIX_JAR="/opt/my #1 build/flix.jar"} into {@code "/opt/my} — truncated, and
     * still carrying its opening quote, because the closing one had just been cut off.
     *
     * <p>An unterminated quote falls through to the unquoted branch and is left as written, on the
     * same grounds as an unresolved reference: a visibly wrong value beats a plausible one.
     */
    private static Unquoted readValue(String rhs) {
        String value = rhs.trim();
        for (char quote : new char[] { '\'', '"' }) {
            if (!value.isEmpty() && value.charAt(0) == quote) {
                int end = value.indexOf(quote, 1);
                if (end >= 0) {
                    return new Unquoted(value.substring(1, end), quote == '\'');
                }
            }
        }
        return new Unquoted(stripTrailingComment(value), false);
    }

    private record Unquoted(String value, boolean literal) {
    }

    /**
     * Expands the variable references direnv would have expanded.
     *
     * <p>{@code PWD} is the project root rather than this process's working directory: the file is
     * evaluated by direnv after entering the directory, so {@code $PWD} in an {@code .envrc} means
     * the project — and the template uses it for exactly that
     * ({@code FLIX_CACHE_HOME="$PWD/.flixw/local/cache"}). Taking the IDE's own working directory
     * would point a project cache at wherever the IDE happened to be started.
     *
     * <p>Anything still unresolved is left as written. A path with a {@code $} in it fails to
     * resolve visibly, where substituting empty would silently produce a plausible wrong path.
     */
    private static String expand(String value, Path root, Map<String, String> exported) {
        Matcher matcher = REFERENCE.matcher(value);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            String replacement = switch (name) {
                case "PWD" -> root.toAbsolutePath().toString();
                case "HOME" -> System.getProperty("user.home", "");
                default -> {
                    String known = exported.get(name);
                    if (known == null) {
                        known = System.getenv(name);
                    }
                    yield known;
                }
            };
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    replacement == null ? matcher.group() : replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
