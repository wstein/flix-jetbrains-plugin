package dev.wstein.flixplugin;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Which Flix subcommands this plugin offers, and how they reach a command line.
 *
 * <p>A subcommand name is not something to get approximately right. The compiler's argument parser
 * demotes an unrecognized command to a positional argument, so {@code flix buildjar} does not fail
 * with "no such command" — it reports {@code Unrecognized file extension: 'buildjar'}, which names
 * neither the typo nor the command list. That failure surfaces only when a user clicks the menu
 * item, so the names are checked against the compiler itself rather than reviewed.
 */
public class FlixTaskTest {

    /** `flix --help` prints one `Command: <name>` line per subcommand. */
    private static final Pattern COMMAND_LINE = Pattern.compile("^Command: (\\S+)", Pattern.MULTILINE);

    /** `Main.scala` declares each subcommand as `cmd("<name>")`. */
    private static final Pattern COMMAND_DECLARATION = Pattern.compile("cmd\\(\"([^\"]+)\"\\)");

    @Test
    public void everyTaskNamesACommandTheCompilerAccepts() throws IOException, InterruptedException {
        List<String> offered = commandNames();
        Set<String> accepted = commandsTheCompilerDefines();

        List<String> unknown = new ArrayList<>(offered);
        unknown.removeAll(accepted);
        assertTrue("subcommands the compiler does not accept: " + unknown + " (it accepts " + accepted + ")",
                unknown.isEmpty());
    }

    @Test
    public void noTwoTasksShareACommand() {
        // byCommand resolves a persisted configuration back to a task; two tasks with one name would
        // make that resolution depend on declaration order.
        Set<String> distinct = new HashSet<>(commandNames());
        assertEquals(FlixTask.values().length, distinct.size());
    }

    @Test
    public void aTaskRoundTripsThroughItsCommand() {
        for (FlixTask task : FlixTask.values()) {
            assertEquals(Optional.of(task), FlixTask.byCommand(task.command()));
        }
    }

    @Test
    public void anUnknownCommandResolvesToNothing() {
        // A run configuration persists the command, so a task that is renamed or removed comes back
        // as a name nothing matches. Falling back to a default would run `clean` or `build-jar`
        // when the user asked for something else.
        assertEquals(Optional.empty(), FlixTask.byCommand("build-jarr"));
        assertEquals(Optional.empty(), FlixTask.byCommand(""));
        assertEquals(Optional.empty(), FlixTask.byCommand(null));
    }

    @Test
    public void everyTaskIsPresentableWithoutQuoting() {
        for (FlixTask task : FlixTask.values()) {
            assertFalse(task.title().isBlank());
            assertFalse(task.description().isBlank());
            // A command with a space would need quoting on the command line, which nothing does.
            assertFalse(task.command() + " needs quoting", task.command().contains(" "));
        }
    }

    @Test
    public void jvmOptionsGoBeforeTheJarAndTaskOptionsAfterTheSubcommand() {
        // The whole reason the two lists are separate. A JVM option after `-jar` is the compiler's
        // input; a compiler option before the subcommand is treated as global and stops command
        // parsing, so `flix --Xdebug run` runs nothing.
        List<String> command = FlixLaunchCommand.task(
                Path.of("/flix.jar"), "build", List.of("-Xmx2g"), List.of("--github-token", "t"));

        assertEquals(
                List.of("java", "-Xmx2g", "-jar", "/flix.jar", "build", "--github-token", "t"),
                command);
    }

    @Test
    public void aTaskWithNoExtraArgumentsIsJustTheSubcommand() {
        assertEquals(
                List.of("java", "-jar", "/flix.jar", "test"),
                FlixLaunchCommand.task(Path.of("/flix.jar"), "test", List.of(), List.of()));
    }

    @Test
    public void runStillBuildsTheInvocationItAlwaysDid() {
        // `run` now goes through the same builder as every other task. The ordering rules it
        // encodes are the ones FlixLaunchCommandTest already pins; this only checks that routing it
        // through `task` did not move anything.
        assertEquals(
                List.of("java", "-jar", "/flix.jar", "run", "--entrypoint", "Main.main"),
                FlixLaunchCommand.run(Path.of("/flix.jar"), "Main.main"));
    }

    /** Every subcommand this plugin offers. */
    private static List<String> commandNames() {
        return Arrays.stream(FlixTask.values()).map(FlixTask::command).toList();
    }

    /**
     * The subcommands the compiler defines, from whichever of its two forms is present.
     *
     * <p>A Flix source checkout first, because that is what CI has: the workflow clones
     * {@code flix/flix} at {@code flixCorpusCommit} for the parser corpus gate and passes it as
     * {@code -PflixCorpusDir}. {@code Main.scala} is where the subcommands are declared, so reading
     * it is reading the same line the argument parser is built from.
     *
     * <p>A built jar otherwise, since {@code flix --help} prints one {@code Command:} line per
     * subcommand and a developer working against a jar may have no checkout.
     *
     * <p>Skipped when neither exists — and failed rather than skipped under {@code CI}, where a
     * skip is indistinguishable from a pass. An earlier version of this asserted that under CI a
     * *jar* must exist, which was simply wrong: CI provides a source checkout and has never built
     * a compiler, so the gate failed the build on every run.
     */
    private static Set<String> commandsTheCompilerDefines() throws IOException, InterruptedException {
        Set<String> fromSource = commandsFromMainScala();
        if (fromSource != null) {
            return fromSource;
        }
        Set<String> fromJar = commandsFromCompilerHelp();
        if (fromJar != null) {
            return fromJar;
        }
        assertFalse(
                "CI must run this gate. It needs either the Flix checkout the corpus gate already "
                        + "clones (-PflixCorpusDir) or a compiler jar (FLIX_JAR).",
                System.getenv("CI") != null);
        assumeTrue("no Flix checkout and no compiler jar; set FLIX_DIR or FLIX_JAR to run this", false);
        return Set.of();
    }

    /** Every {@code cmd("…")} in the checkout's {@code Main.scala}, or {@code null} if absent. */
    private static Set<String> commandsFromMainScala() throws IOException {
        Path checkout = locateCheckout();
        if (checkout == null) {
            return null;
        }
        Path main = checkout.resolve("main/src/ca/uwaterloo/flix/Main.scala");
        if (!Files.isRegularFile(main)) {
            return null;
        }
        Set<String> commands = new HashSet<>();
        Matcher matcher = COMMAND_DECLARATION.matcher(Files.readString(main));
        while (matcher.find()) {
            commands.add(matcher.group(1));
        }
        assertFalse("no cmd(\"…\") declarations in " + main, commands.isEmpty());
        return commands;
    }

    /** Every {@code Command:} line of {@code flix --help}, or {@code null} without a jar. */
    private static Set<String> commandsFromCompilerHelp() throws IOException, InterruptedException {
        Path jar = locateJar();
        if (jar == null) {
            return null;
        }
        Process process = new ProcessBuilder("java", "-jar", jar.toString(), "--help")
                .redirectErrorStream(true)
                .start();
        String help = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();

        Set<String> commands = new HashSet<>();
        Matcher matcher = COMMAND_LINE.matcher(help);
        while (matcher.find()) {
            commands.add(matcher.group(1));
        }
        assertFalse("`flix --help` listed no commands; output was:\n" + help, commands.isEmpty());
        return commands;
    }

    /**
     * The Flix source checkout, located the way the corpus gate locates it.
     *
     * <p>The same two knobs, so one setting serves both gates rather than each having its own.
     */
    private static Path locateCheckout() {
        for (String candidate : new String[]{System.getProperty("flixCorpusDir"), System.getenv("FLIX_DIR")}) {
            if (candidate != null && !candidate.isBlank() && new File(candidate.trim()).isDirectory()) {
                return Path.of(candidate.trim());
            }
        }
        return null;
    }

    /** {@code $FLIX_JAR}, else {@code flix.jar} beside the repository, else {@code null}. */
    private static Path locateJar() {
        String pinned = System.getenv(FlixJar.JAR_ENV);
        if (pinned != null && !pinned.isBlank() && new File(pinned.trim()).isFile()) {
            return Path.of(pinned.trim());
        }
        Path candidate = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 4 && candidate != null; depth++) {
            Path jar = candidate.resolve("flix.jar");
            if (Files.isRegularFile(jar)) {
                return jar;
            }
            candidate = candidate.getParent();
        }
        return null;
    }
}
