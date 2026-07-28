package dev.wstein.flixplugin;

import org.junit.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The Flix launch invocation.
 *
 * <p>Every rule asserted here was learned from a failure rather than from documentation, which is
 * why they are pinned: the compiler's argument parser is order-sensitive in a way that turns a
 * misplaced flag into a confusing error about something else entirely, and {@code --Xdebug}
 * silently changes what debug information the class files carry.
 */
public class FlixLaunchCommandTest {

    private static final Path JAR = Path.of("/opt/flix/flix-vendor.jar");

    @Test
    public void runIsTheSubcommandAndComesBeforeAnyOption() {
        // The rule that matters most. An option seen before the subcommand is parsed as global and
        // stops command parsing, so `flix --Xdebug run` reports `Unrecognized file extension:
        // 'run'` -- an error that names neither the flag nor the real problem.
        List<String> command = FlixLaunchCommand.debug(JAR, null);
        assertEquals("run", command.get(3));
        assertTrue(
                "every option must follow the subcommand",
                command.indexOf("--Xdebug") > command.indexOf("run"));
    }

    @Test
    public void debugPassesXdebugExactlyOnce() {
        // A second --Xdebug after the subcommand is rejected as `Unknown option --Xdebug`, so a
        // wrapper that injects one and a caller that also passes one combine into a failure.
        List<String> command = FlixLaunchCommand.debug(JAR, "Main.main");
        assertEquals(1, command.stream().filter("--Xdebug"::equals).count());
    }

    @Test
    public void runDoesNotPassXdebug() {
        List<String> command = FlixLaunchCommand.run(JAR, "Main.main");
        assertTrue("a plain run must not request debug information", !command.contains("--Xdebug"));
        assertTrue(!command.contains("--yes"));
    }

    @Test
    public void debugAnswersTheDependencyPrompt() {
        // Nobody is watching a terminal for a launch started from the IDE.
        assertTrue(FlixLaunchCommand.debug(JAR, null).contains("--yes"));
    }

    @Test
    public void passesTheEntryPointWhenGiven() {
        List<String> command = FlixLaunchCommand.run(JAR, "Foo.Bar.demo");
        int flag = command.indexOf("--entrypoint");
        assertTrue(flag > 0);
        assertEquals("Foo.Bar.demo", command.get(flag + 1));
    }

    @Test
    public void omitsTheEntryPointFlagEntirelyWhenAbsentOrBlank() {
        // An empty --entrypoint is rejected by the compiler, so a blank symbol has to drop the flag
        // rather than pass nothing after it.
        for (String blank : new String[]{null, "", "   "}) {
            assertTrue(
                    "blank entry point " + (blank == null ? "null" : "'" + blank + "'"),
                    !FlixLaunchCommand.run(JAR, blank).contains("--entrypoint"));
        }
    }

    @Test
    public void trimsTheEntryPoint() {
        assertEquals("Main.main", FlixLaunchCommand.normalizeEntryPoint("  Main.main  "));
        assertNull(FlixLaunchCommand.normalizeEntryPoint("   "));
        assertNull(FlixLaunchCommand.normalizeEntryPoint(null));
    }

    @Test
    public void jdwpAgentSuspendsByRequest() {
        assertTrue(FlixLaunchCommand.withJdwpAgent(null, 5005, true).contains("suspend=y"));
        assertTrue(FlixLaunchCommand.withJdwpAgent(null, 5005, false).contains("suspend=n"));
        assertTrue(FlixLaunchCommand.withJdwpAgent(null, 5005, true).contains("address=*:5005"));
    }

    @Test
    public void jdwpAgentAppendsToExistingJavaToolOptions() {
        // Replacing the variable would silently drop heap or encoding settings for the duration of
        // a debug session -- a difference that only appears while debugging, which is the hardest
        // kind to notice.
        String merged = FlixLaunchCommand.withJdwpAgent("-Xmx2g -Dfile.encoding=UTF-8", 5005, true);
        assertTrue(merged.startsWith("-Xmx2g -Dfile.encoding=UTF-8 "));
        assertTrue(merged.contains("-agentlib:jdwp="));
    }

    @Test
    public void jdwpAgentIgnoresABlankExistingValue() {
        assertTrue(FlixLaunchCommand.withJdwpAgent("   ", 5005, true).startsWith("-agentlib:jdwp="));
    }

    // --- inherited debug agents ------------------------------------------------------------

    @Test
    public void refusesToAddASecondAgentAlongsideAgentlib() {
        // Appending would give the debuggee two JDWP servers competing for suspension, breakpoints
        // and lifecycle -- the state ADR 0002 forbids. The JVM's own failure is a transport error
        // at startup, far from the setting that caused it.
        var thrown = assertThrows(
                FlixLaunchCommand.JdwpAlreadyConfiguredException.class,
                () -> FlixLaunchCommand.withJdwpAgent(
                        "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005",
                        5006,
                        true));
        assertTrue(thrown.getInheritedAgent().startsWith("-agentlib:jdwp"));
        assertTrue(
                "the message must say what to do, not only what is wrong",
                thrown.getMessage().contains("Remote JVM Debug"));
    }

    @Test
    public void refusesToAddASecondAgentAlongsideTheLegacyXrunjdwp() {
        // The pre-JVMTI spelling. Still accepted by every HotSpot, and still what older tooling and
        // copied shell snippets emit, so detecting only -agentlib would miss real cases.
        assertThrows(
                FlixLaunchCommand.JdwpAlreadyConfiguredException.class,
                () -> FlixLaunchCommand.withJdwpAgent(
                        "-Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=8000", 5005, true));
    }

    @Test
    public void refusesRegardlessOfWhereTheAgentSitsInTheOptions() {
        assertThrows(
                FlixLaunchCommand.JdwpAlreadyConfiguredException.class,
                () -> FlixLaunchCommand.withJdwpAgent(
                        "-Xmx2g -agentlib:jdwp=transport=dt_socket,address=*:5005 -Dfoo=bar",
                        5006,
                        true));
    }

    @Test
    public void preservesOrdinaryOptionsThatMerelyMentionTheAgent() {
        // A substring match would reject this. Tokenizing does not: the value of a -D property is
        // not an option that loads an agent, and refusing it would block a legitimate launch.
        String merged = FlixLaunchCommand.withJdwpAgent("-Dexample=-agentlib:jdwp -Xmx2g", 5005, true);
        assertTrue(merged.startsWith("-Dexample=-agentlib:jdwp -Xmx2g "));
        assertTrue(merged.endsWith("address=*:5005"));
    }

    @Test
    public void findsNoAgentInOrdinaryOptions() {
        assertNull(FlixLaunchCommand.findJdwpAgent(null));
        assertNull(FlixLaunchCommand.findJdwpAgent("   "));
        assertNull(FlixLaunchCommand.findJdwpAgent("-Xmx2g -Dfile.encoding=UTF-8"));
        // -agentlib for something other than jdwp is not a debug agent.
        assertNull(FlixLaunchCommand.findJdwpAgent("-agentlib:jvmtiprof"));
    }

    @Test
    public void findsAPortThatCanActuallyBeBound() throws IOException {
        // The port has to be free at the moment it is handed out, which means the allocator must
        // release it. A probe that stayed connected would consume the single connection a JDWP
        // server accepts, and the debugger would find nothing to attach to.
        int port = FlixLaunchCommand.findFreePort();
        assertTrue(port > 0);
        try (ServerSocket rebind = new ServerSocket(port)) {
            assertEquals(port, rebind.getLocalPort());
        }
    }
}
