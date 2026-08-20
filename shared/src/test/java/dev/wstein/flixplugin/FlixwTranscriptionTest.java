package dev.wstein.flixplugin;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Keeps {@link FlixwProject} honest about the wrapper it transcribes.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code FlixwProject} is a second implementation of flixw's cache layout. It defended that by
 * citing the wrapper line by line — {@code flixw.java:466} for the digest format,
 * {@code flixw.java:974-996} for the JDK search, and four more. Every one of those citations was
 * wrong within about eight days: in wrapper 0.20.3, line 466 is a backslash-unescape loop, 974 is
 * blank, and 1315 is {@code --install-jdk} usage text. The <em>behaviour</em> still matched, by
 * luck; the mechanism that was supposed to bound the cost of the duplication had silently expired,
 * and nothing said so.
 *
 * <p>So the citations are gone from the code and the checking is here instead. Line numbers cannot
 * be verified by anything; a method body can.
 *
 * <h2>What it asserts, and why that shape</h2>
 *
 * <p>The <em>normalised body</em> of each transcribed method, compared against what this plugin was
 * written from. Not "the anchor is still somewhere in the file": a rule can move, be rewritten, or
 * grow a branch while every keyword in it survives. Comparing bodies means any edit at all — even a
 * cosmetic one — fails this and makes someone re-read the rule against
 * {@link FlixwProject}. That is deliberate. The failure being noisy is the cheap half; the
 * expensive half is a layout change that this plugin follows six months late, which shows up as a
 * project that opens without a compiler and no clue why.
 *
 * <h2>Pointing it at a checkout</h2>
 *
 * <p>{@code -PflixwDir=/path/to/flixw}, or {@code FLIXW_DIR}. Skips when there is none, because most
 * work on this plugin needs no wrapper checkout — and <b>fails when {@code CI} is set</b>, for the
 * reason {@code FlixCorpusTest} gives: a gate that skips in the one place it is supposed to run is
 * reported as a pass.
 */
public class FlixwTranscriptionTest {

    /** The wrapper's own source, which is what a project's {@code .flixw/flixw.java} is vendored from. */
    private static final String WRAPPER_SOURCE = "src/stage0/flixw.java";

    private static final String HOW_TO_POINT_AT_A_CHECKOUT =
            "Point at one with -PflixwDir=/path/to/flixw or FLIXW_DIR=/path/to/flixw.";

    /**
     * Each transcribed rule, as the wrapper spells it.
     *
     * <p>Keyed by the wrapper's method signature and valued by its normalised body. When one of
     * these fails, the fix is never to paste the new text in: it is to re-read
     * {@link FlixwProject}'s corresponding method against the wrapper's, decide whether the plugin
     * still agrees, and only then update this.
     */
    private static final Map<String, String> TRANSCRIBED = Map.of(
            // FlixwProject.cacheHome — where anything flixw verified lives. A change here moves
            // every pinned compiler out from under the plugin at once.
            "static Path cacheHome() {",
            "String o = env(\"FLIX_CACHE_HOME\"); "
                    + "if (o != null) return Paths.get(o).toAbsolutePath(); "
                    + "String home = System.getProperty(\"user.home\"); "
                    + "if (isWindows()) { String local = env(\"LOCALAPPDATA\"); "
                    + "return Paths.get(local != null ? local : home).resolve(\"flixw\"); } "
                    + "if (isMac()) return Paths.get(home, \"Library\", \"Caches\", \"flixw\"); "
                    + "String xdg = env(\"XDG_CACHE_HOME\"); "
                    + "return (xdg != null ? Paths.get(xdg) : Paths.get(home, \".cache\")).resolve(\"flixw\");",

            // FlixwProject.existingJar — the jar's file name, digest and all. Getting this wrong
            // means the plugin reports "nothing pinned" for a project that has a verified compiler.
            "static Path compilerPath(Lock lock) {",
            "return cacheHome().resolve(\"compilers\") "
                    + ".resolve(\"flix-\" + canonical(lock.version()) + \"-\" + lock.sha256() + \".jar\");",

            // FlixwProject.canonical — build metadata is dropped from the version but not from the
            // digest, so a fork's `0.75.2+fork...` pin names `flix-0.75.2-<digest>.jar`.
            "static String canonical(String v) {",
            "int i = v.indexOf('+'); return i < 0 ? v : v.substring(0, i);");

    @Test
    public void theTranscribedRulesStillReadAsTheyWereTranscribed() throws IOException {
        Path wrapper = wrapperSource();
        if (wrapper == null) {
            failIfRequired();
            System.out.println("[flixw] no wrapper checkout; skipping. " + HOW_TO_POINT_AT_A_CHECKOUT);
            return;
        }
        String source = Files.readString(wrapper, StandardCharsets.UTF_8);

        for (Map.Entry<String, String> rule : TRANSCRIBED.entrySet()) {
            String body = bodyOf(source, rule.getKey());
            assertTrue("flixw no longer declares `" + rule.getKey() + "`, which "
                    + "FlixwProject transcribes. Re-derive it against " + wrapper, body != null);
            assertEquals("flixw changed `" + rule.getKey() + "`. Re-read FlixwProject against "
                    + wrapper + " and decide whether the plugin still agrees before updating this.",
                    rule.getValue(), body);
        }
    }

    @Test
    public void theDigestIsStillSixtyFourLowercaseHexDigits() throws IOException {
        Path wrapper = wrapperSource();
        if (wrapper == null) {
            failIfRequired();
            return;
        }
        // The digest is interpolated into a file name the plugin then opens, so its shape is a
        // security property and not a formatting detail: a lock naming `../../../etc/passwd` would
        // otherwise be a path traversal out of the cache. FlixwProject enforces `[0-9a-f]{64}`,
        // which is exactly what this format produces.
        assertTrue("flixw no longer writes digests as %064x; FlixwProject.DIGEST assumes it does",
                Files.readString(wrapper, StandardCharsets.UTF_8).contains("String.format(\"%064x\""));
    }

    @Test
    public void theJdkMarkerIsStillWhereTheJdkSearchLooks() throws IOException {
        Path wrapper = wrapperSource();
        if (wrapper == null) {
            failIfRequired();
            return;
        }
        // FlixwProject.installedJdk reads this file and then executes what it names, which is why it
        // resolves both sides before deciding the path is inside the cache.
        assertTrue("flixw no longer records its JDK at jdks/default",
                Files.readString(wrapper, StandardCharsets.UTF_8)
                        .contains("cacheHome().resolve(\"jdks\").resolve(\"default\")"));
    }

    /**
     * The body of the method whose declaration starts with {@code signature}, whitespace collapsed.
     *
     * <p>Brace-counted rather than matched, because a method body is not a regular language. Braces
     * inside string and character literals are skipped: the bodies here contain quoted text, and one
     * counted brace would truncate the comparison to something that still looks plausible.
     */
    private static String bodyOf(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) {
            return null;
        }
        int open = start + signature.length() - 1;
        int depth = 0;
        boolean inString = false;
        boolean inChar = false;
        boolean escaped = false;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (inString) {
                inString = c != '"';
            } else if (inChar) {
                inChar = c != '\'';
            } else if (c == '"') {
                inString = true;
            } else if (c == '\'') {
                inChar = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open + 1, i).replaceAll("\\s+", " ").trim();
                }
            }
        }
        return null;
    }

    /** The wrapper's own source, or {@code null} when no checkout was given or found. */
    private static Path wrapperSource() {
        String configured = System.getProperty("flixwDir", System.getenv("FLIXW_DIR"));
        List<String> candidates = configured != null && !configured.isBlank()
                ? List.of(configured)
                // A sibling checkout, which is where it sits for anyone working on both.
                : List.of("../flixw", System.getProperty("user.home", "") + "/github.com/wstein/flixw");
        for (String candidate : candidates) {
            Path source = Path.of(candidate).resolve(WRAPPER_SOURCE);
            if (Files.isRegularFile(source)) {
                return source;
            }
        }
        return null;
    }

    /** An absent checkout is a skip locally and a failure in CI. See the class comment. */
    private static void failIfRequired() {
        String ci = System.getenv("CI");
        if (ci == null || ci.isBlank()) {
            ci = System.getenv("GITHUB_ACTIONS");
        }
        if (ci != null && !ci.isBlank()) {
            fail("No flixw checkout found, but CI is expected to provide one: this is the only "
                    + "check that would notice the wrapper's cache layout moving out from under "
                    + "FlixwProject, and skipping it reports that as a pass. " + HOW_TO_POINT_AT_A_CHECKOUT);
        }
    }
}
