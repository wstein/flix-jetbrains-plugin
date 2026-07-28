package dev.wstein.flixplugin;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the command line and JDWP wiring for running or debugging a Flix program.
 *
 * <p>Shared rather than duplicated because two callers need the identical invocation -- the
 * {@code flix.runMain} CodeLens action and the native JVM debug configuration -- and the
 * invocation has non-obvious rules that were each learned from a failure:
 *
 * <ul>
 *   <li><b>Options go after the subcommand.</b> The compiler's argument parser treats an option
 *       seen before a command as global and then stops parsing commands, so
 *       {@code flix --Xdebug run} does not run anything: {@code run} is demoted to a positional
 *       argument and rejected as {@code Unrecognized file extension: 'run'}. Only
 *       {@code flix run --Xdebug} works.
 *   <li><b>{@code --Xdebug} exactly once.</b> A second occurrence after the subcommand is reported
 *       as {@code Unknown option --Xdebug}, so a wrapper script that injects it and a caller that
 *       also passes it combine into a failure rather than a redundancy.
 *   <li><b>{@code --Xdebug} is not only a JDWP switch.</b> It also decides which lines the compiler
 *       records: {@code Let}, {@code ApplyDef}, {@code ApplyClo}, {@code IfThenElse} and
 *       {@code Stm} emit line numbers only under it, and it stops the inliner discarding
 *       programmer-written bindings. Debugging without it produces a program whose statements have
 *       no breakpointable lines.
 * </ul>
 */
public final class FlixLaunchCommand {

    /** The JDWP agent argument, kept in one place so the transport and suspend policy agree. */
    private static final String JDWP_AGENT =
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=%s,address=*:%d";

    private FlixLaunchCommand() {
    }

    /**
     * {@code java -jar <jar> run [--entrypoint <symbol>]}.
     *
     * @param entryPoint the entry point to run, or {@code null} for the project default
     */
    public static @NotNull List<String> run(@NotNull Path jar, @Nullable String entryPoint) {
        return build(jar, entryPoint, false);
    }

    /**
     * {@code java -jar <jar> run --Xdebug --yes [--entrypoint <symbol>]}.
     *
     * <p>{@code --yes} answers the dependency-resolution prompt, which would otherwise block a
     * launch nobody is watching a terminal for.
     */
    public static @NotNull List<String> debug(@NotNull Path jar, @Nullable String entryPoint) {
        return build(jar, entryPoint, true);
    }

    /**
     * {@code java -agentlib:jdwp=… -jar <jar> run --Xdebug --yes [--entrypoint <symbol>]}.
     *
     * <p>Puts the agent on the command line rather than in {@code JAVA_TOOL_OPTIONS}. Both work --
     * the gate was run with the environment variable -- but an argument is visible in the run
     * console, which matters when the question is "which debugger am I actually on"; the variable
     * is invisible once the process has started.
     *
     * <p>It must sit before {@code -jar}: everything after the jar is the Flix compiler's own
     * argument list, where a JVM option is either ignored or rejected.
     *
     * <p>Does not remove the caller's obligation to check {@code JAVA_TOOL_OPTIONS} with
     * {@link #findJdwpAgent}: an inherited agent would still start a second JDWP server in the same
     * JVM, which is the state ADR 0002 forbids.
     *
     * @param suspend whether the debuggee waits for the debugger before running any user code
     */
    public static @NotNull List<String> debug(
            @NotNull Path jar, @Nullable String entryPoint, int jdwpPort, boolean suspend) {
        List<String> command = new ArrayList<>(build(jar, entryPoint, true));
        command.add(1, jdwpAgent(jdwpPort, suspend));
        return command;
    }

    /** The {@code -agentlib:jdwp} argument for [jdwpPort]. */
    public static @NotNull String jdwpAgent(int port, boolean suspend) {
        return JDWP_AGENT.formatted(suspend ? "y" : "n", port);
    }

    private static List<String> build(Path jar, String entryPoint, boolean debug) {
        List<String> command = new ArrayList<>(List.of("java", "-jar", jar.toString(), "run"));
        if (debug) {
            command.add("--Xdebug");
            command.add("--yes");
        }
        String symbol = normalizeEntryPoint(entryPoint);
        if (symbol != null) {
            command.add("--entrypoint");
            command.add(symbol);
        }
        return command;
    }

    /**
     * The entry-point symbol to pass, or {@code null} to leave it to the project default.
     *
     * <p>A blank value must become {@code null} rather than an empty {@code --entrypoint}, which
     * the compiler rejects with an error the user cannot act on.
     */
    public static @Nullable String normalizeEntryPoint(@Nullable String entryPoint) {
        if (entryPoint == null) {
            return null;
        }
        String trimmed = entryPoint.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * The value to give {@code JAVA_TOOL_OPTIONS} so the launched JVM listens for a debugger.
     *
     * <p>Appends to any existing value rather than replacing it: that variable is a common way to
     * set heap or encoding options, and silently dropping the user's settings when a debug session
     * starts would change how the program runs only while being debugged -- the hardest kind of
     * difference to notice.
     *
     * @throws JdwpAlreadyConfiguredException if the inherited options already load a debug agent
     */
    public static @NotNull String withJdwpAgent(@Nullable String existingOptions, int port, boolean suspend) {
        String inherited = existingOptions == null ? "" : existingOptions.strip();
        String alreadyPresent = findJdwpAgent(inherited);
        if (alreadyPresent != null) {
            throw new JdwpAlreadyConfiguredException(alreadyPresent);
        }
        String agent = jdwpAgent(port, suspend);
        return inherited.isEmpty() ? agent : inherited + " " + agent;
    }

    /**
     * The inherited debug-agent option, or {@code null} if there is none.
     *
     * <p>Tokenizes rather than substring-matching, so an option that merely mentions the text --
     * {@code -Dsomething=-agentlib:jdwp}, a value in a property -- is not mistaken for one that
     * loads an agent.
     *
     * <p>Both spellings are checked. {@code -agentlib:jdwp} is current; {@code -Xrunjdwp} is the
     * pre-JVMTI form, still accepted by every HotSpot and still what older tooling and copied
     * shell snippets emit.
     */
    public static @Nullable String findJdwpAgent(@Nullable String options) {
        if (options == null || options.isBlank()) {
            return null;
        }
        for (String token : options.strip().split("\\s+")) {
            if (token.startsWith("-agentlib:jdwp") || token.startsWith("-Xrunjdwp")) {
                return token;
            }
        }
        return null;
    }

    /**
     * Raised when the environment already loads a debug agent.
     *
     * <p>Appending a second one is not a redundancy: two agents mean two JDWP servers competing for
     * suspension, breakpoints and lifecycle, which is the state ADR 0002 forbids. The JVM's own
     * failure for this is a startup error about transport initialization, far from the setting that
     * caused it, so this refuses in the IDE where the user can act on it.
     */
    public static final class JdwpAlreadyConfiguredException extends IllegalStateException {
        private final String inheritedAgent;

        JdwpAlreadyConfiguredException(@NotNull String inheritedAgent) {
            super("JAVA_TOOL_OPTIONS already loads a debug agent (" + inheritedAgent + "), so this "
                    + "launch would start a second one. Two agents cannot share a debuggee: they "
                    + "compete for suspension, breakpoints and lifecycle. Remove the agent from "
                    + "JAVA_TOOL_OPTIONS and let the run configuration supply it, or attach to the "
                    + "existing one with a Remote JVM Debug configuration instead.");
            this.inheritedAgent = inheritedAgent;
        }

        /** The offending option, for a caller that wants to name it in its own message. */
        public @NotNull String getInheritedAgent() {
            return inheritedAgent;
        }
    }

    /**
     * A free TCP port for the JDWP listener.
     *
     * <p>Binds and releases rather than probing by connecting. Connecting to a candidate port to
     * test it can consume the very listener the debugger is meant to attach to, and a JDWP server
     * accepts one connection: the probe would take it and the debugger would find nothing.
     *
     * <p>The bind-then-release window is a genuine race -- another process can claim the port
     * before the JVM binds it -- but it is the standard trade, and the failure is a clean
     * "address already in use" at launch rather than a debugger that silently attaches to the
     * wrong thing.
     */
    public static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
