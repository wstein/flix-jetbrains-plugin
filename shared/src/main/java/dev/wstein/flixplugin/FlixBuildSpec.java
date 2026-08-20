package dev.wstein.flixplugin;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How to start the program a Flix build left behind, as the build itself recorded it.
 *
 * <h2>Why this is read rather than computed</h2>
 *
 * <p>Because {@code flix run} starts the program in a JVM of its own. The plugin's debug launch used
 * to put the JDWP agent on {@code flix run}, which put it on the <em>compiler</em>; the program then
 * ran one process further down with no agent, and every breakpoint silently failed to bind. Nothing
 * reported an error, because nothing was wrong with any of the parts.
 *
 * <p>So a debug session has to start the program itself — and then it needs three facts the compiler
 * owns: which classpath, which class, which {@code java}. Computing them here would be a second
 * implementation of {@code ProjectView.runtimeClasspath}, {@code ProgramRunner.MainClass} and
 * {@code ProgramRunner.javaBinary}, which must agree with the first or the program fails to start
 * for a reason naming none of them. The compiler writes them into {@code build.json} instead
 * ({@code BuildManifest.LaunchSpec}, format 4), and this reads that.
 *
 * <p>{@link #java()} in particular is <em>not</em> the plugin's choice. It is the JVM the compiler
 * ran on, and the program was compiled for that release: starting it on an older one fails with a
 * class-version error that says nothing about why. {@link FlixJar#javaExecutable} answers a
 * different question — which JVM to run the <em>compiler</em> on — and the two must not be confused.
 *
 * <h2>Why the JSON is parsed by hand</h2>
 *
 * <p>This module carries no dependency at all, on the platform or on anything else, because it is
 * loaded in every process ({@code shared}). Three string fields off a flat object do not justify
 * being the first. The parse is deliberately narrow and refuses anything it does not recognise
 * rather than guessing: a malformed manifest that produced a half-right classpath would fail as a
 * missing class at run time, which is the failure this class exists to remove.
 */
public final class FlixBuildSpec {

    /** Where the compiler writes the manifest, relative to the project root. */
    public static final String MANIFEST_PATH = "build/development/build.json";

    /** The manifest format this understands. Anything else is a compiler that records something else. */
    private static final int FORMAT_VERSION = 4;

    private final String java;
    private final String mainClass;
    private final List<String> runtimeClasspath;

    private FlixBuildSpec(String java, @Nullable String mainClass, List<String> runtimeClasspath) {
        this.java = java;
        this.mainClass = mainClass;
        this.runtimeClasspath = List.copyOf(runtimeClasspath);
    }

    /** The absolute {@code java} the program must be started with. */
    public @NotNull String java() {
        return java;
    }

    /** The entry-point class, or {@code null} for a build with no {@code main}. */
    public @Nullable String mainClass() {
        return mainClass;
    }

    /** The classpath entries, in order. Order matters: the class directory shadows the jars. */
    public @NotNull List<String> runtimeClasspath() {
        return runtimeClasspath;
    }

    /** The manifest of the development build under {@code projectRoot}. */
    public static @NotNull Path manifestIn(@NotNull Path projectRoot) {
        return projectRoot.resolve(MANIFEST_PATH).normalize();
    }

    /**
     * Reads the launch spec recorded by the last development build of the project at {@code root}.
     *
     * @throws IllegalStateException naming what to do, when there is no manifest, when it was written
     *                               by a compiler that records a different format, or when it carries
     *                               no launch spec. Every one of those is "build first", and saying
     *                               so is the whole value of failing here rather than launching
     *                               something wrong.
     */
    public static @NotNull FlixBuildSpec read(@NotNull Path root) {
        Path manifest = manifestIn(root);
        String json;
        try {
            json = Files.readString(manifest, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("No build manifest at " + manifest
                    + ". Build the project before debugging it.", e);
        }

        int format = intField(json, "formatVersion");
        if (format != FORMAT_VERSION) {
            throw new IllegalStateException(manifest + " is format " + format + ", and this plugin reads "
                    + FORMAT_VERSION + ". The compiler that wrote it does not record how to start the "
                    + "program, so a debug session cannot attach to it.");
        }

        String launch = objectField(json, "launch");
        if (launch == null) {
            throw new IllegalStateException(manifest + " records no launch spec. "
                    + "Rebuild the project with a compiler that writes one.");
        }

        String java = stringField(launch, "java");
        if (java == null) {
            throw new IllegalStateException(manifest + " records no `java` to start the program with.");
        }
        return new FlixBuildSpec(java, stringField(launch, "mainClass"), stringArrayField(launch, "runtimeClasspath"));
    }

    /**
     * The {@code -cp} value for this spec.
     *
     * <p>{@link File#pathSeparator} is what the compiler joins with when it forks the program
     * itself, so this is the same string that run would have used.
     */
    public @NotNull String classpath() {
        return String.join(File.pathSeparator, runtimeClasspath);
    }

    // ---------------------------------------------------------------------------------------------
    // A parser for exactly the shape the compiler writes, and nothing else.
    //
    // json4s pretty-prints one key per line with no escaping beyond JSON's own, so a field is found
    // by its quoted name and read to the end of its value. Paths are the only strings here that can
    // contain an escape, and `\\` and `\"` are the only two that matter on any platform this runs on.
    // ---------------------------------------------------------------------------------------------

    private static int intField(String json, String name) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        if (!m.find()) {
            throw new IllegalStateException("The build manifest has no `" + name + "`; it is not one this plugin reads.");
        }
        return Integer.parseInt(m.group(1));
    }

    private static @Nullable String stringField(String json, String name) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? unescape(m.group(1)) : null;
    }

    private static @NotNull List<String> stringArrayField(String json, String name) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\\[([^]]*)]").matcher(json);
        List<String> values = new ArrayList<>();
        if (!m.find()) {
            return values;
        }
        Matcher element = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(m.group(1));
        while (element.find()) {
            values.add(unescape(element.group(1)));
        }
        return values;
    }

    /**
     * The text of the object named {@code name}, or {@code null} if there is none.
     *
     * <p>Brace-counted rather than matched with a pattern, because the value is an object and a
     * regular expression cannot bound one. Quoted braces are not counted: a Windows path holds none,
     * but a project directory called {@code {old}} would otherwise truncate the object silently.
     */
    private static @Nullable String objectField(String json, String name) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\\{").matcher(json);
        if (!m.find()) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = m.end() - 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString && c == '{') {
                depth++;
            } else if (!inString && c == '}') {
                depth--;
                if (depth == 0) {
                    return json.substring(m.end() - 1, i + 1);
                }
            }
        }
        return null;
    }

    private static String unescape(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\' || i + 1 >= raw.length()) {
                out.append(c);
                continue;
            }
            char next = raw.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    out.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> out.append(next);
            }
        }
        return out.toString();
    }
}
