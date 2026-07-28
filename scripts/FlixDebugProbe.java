import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;

import java.util.List;
import java.util.Map;

/**
 * Sets a breakpoint on a Flix line, waits for it, and prints the stack it stopped in.
 *
 * <h2>What this evidences, and what it does not</h2>
 *
 * The run configuration's job ends at "a process is running with a JDWP agent on this port, and
 * here is where to attach". Everything after that is the platform's. This exercises that second
 * half end to end -- bind, hit, and walk a stack that mixes Flix with whatever it called -- against
 * the same command line {@code FlixLaunchCommand} builds.
 *
 * <p>It does <b>not</b> prove the green gutter arrow works. Pressing it is the one step no
 * automated check can take: what that exercises is the configuration and producer wiring, and only
 * an IDE can do it. What this removes is every other reason a debug session could fail, so a
 * failure after this passes is narrowed to the button.
 *
 * <h2>Use</h2>
 *
 * <pre>{@code
 * JAVA_TOOL_OPTIONS='-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5099' \
 *   ./scripts/flix-fork run --Xdebug --yes &
 * java scripts/FlixDebugProbe.java 5099 Main.flix 101
 * }</pre>
 *
 * <p>Exits non-zero if the breakpoint never binds or never hits.
 */
public final class FlixDebugProbe {

    private static final long TIMEOUT_MS = 180_000;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: FlixDebugProbe <jdwp-port> <source-file-name> <line>");
            System.exit(2);
        }
        String port = args[0];
        String sourceName = args[1];
        int line = Integer.parseInt(args[2]);

        VirtualMachine vm = attach(port);
        System.out.printf("attached to %s%n%n", vm.name());

        // Watch every class: Flix names encode the definition and carry a counter that changes
        // between builds, so there is no pattern to filter on that survives a recompile.
        ClassPrepareRequest prepare = vm.eventRequestManager().createClassPrepareRequest();
        prepare.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        prepare.enable();
        vm.resume();

        int bound = 0;
        boolean hit = false;
        EventQueue queue = vm.eventQueue();
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;

        while (!hit && System.currentTimeMillis() < deadline) {
            EventSet set;
            try {
                set = queue.remove(2000);
            } catch (Exception e) {
                break;
            }
            if (set == null) {
                continue;
            }
            boolean stop = false;
            for (Event event : set) {
                if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
                    deadline = 0;
                } else if (event instanceof ClassPrepareEvent prepared) {
                    bound += arm(vm, prepared.referenceType(), sourceName, line);
                } else if (event instanceof BreakpointEvent stopped) {
                    hit = true;
                    stop = true;
                    report(stopped, sourceName, line);
                }
            }
            if (!stop) {
                try {
                    set.resume();
                } catch (Exception ignored) {
                    // Racing VM death; the loop's deadline ends it.
                }
            }
        }

        System.out.printf("%nbreakpoint %s:%d  ->  bound in %d class(es), %s%n",
                sourceName, line, bound, hit ? "HIT" : "NEVER HIT");
        try {
            vm.dispose();
        } catch (Exception ignored) {
            // Already gone; the report above is the point.
        }
        System.exit(hit ? 0 : 1);
    }

    private static VirtualMachine attach(String port) throws Exception {
        AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
                .filter(c -> c.name().equals("com.sun.jdi.SocketAttach"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no socket-attach connector"));
        Map<String, Connector.Argument> params = connector.defaultArguments();
        params.get("hostname").setValue("localhost");
        params.get("port").setValue(port);
        return connector.attach(params);
    }

    /** Requests a stop at every location {@code type} has for that line. Returns 1 if any. */
    private static int arm(VirtualMachine vm, ReferenceType type, String sourceName, int line) {
        String stratum = type.availableStrata().contains("Flix") ? "Flix" : type.defaultStratum();
        String matched;
        try {
            matched = type.sourceNames(stratum).stream()
                    .filter(name -> name.endsWith(sourceName))
                    .findFirst()
                    .orElse(null);
        } catch (AbsentInformationException e) {
            return 0;
        }
        if (matched == null) {
            return 0;
        }
        try {
            List<Location> locations = type.locationsOfLine(stratum, matched, line);
            if (locations.isEmpty()) {
                return 0;
            }
            for (Location location : locations) {
                BreakpointRequest request = vm.eventRequestManager().createBreakpointRequest(location);
                request.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                request.enable();
            }
            System.out.printf("  armed %-34s %d location(s)%n", type.name(), locations.size());
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Prints the stack, which is where mixed-language frames become visible. */
    private static void report(BreakpointEvent stopped, String sourceName, int line) {
        System.out.printf("%nSTOPPED at %s:%d%n%n%-6s %-34s %s%n", sourceName, line, "FRAME", "CLASS", "SOURCE");
        try {
            List<StackFrame> frames = stopped.thread().frames();
            for (int i = 0; i < Math.min(frames.size(), 12); i++) {
                Location at = frames.get(i).location();
                String stratum = at.declaringType().availableStrata().contains("Flix")
                        ? "Flix" : at.declaringType().defaultStratum();
                String source;
                try {
                    source = at.sourceName(stratum) + ":" + at.lineNumber(stratum);
                } catch (AbsentInformationException e) {
                    source = "(no source information)";
                }
                System.out.printf("%-6d %-34s %s%n", i, at.declaringType().name(), source);
            }
        } catch (Exception e) {
            System.out.println("  could not walk the stack: " + e);
        }
    }
}
