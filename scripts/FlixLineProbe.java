import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.ClassPrepareRequest;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Asks a live VM whether each line of a Flix source can take a breakpoint.
 *
 * <h2>Why this exists</h2>
 *
 * {@code javap} showing a line in the {@code LineNumberTable} does <b>not</b> mean a debugger can
 * bind to it. Only {@link ReferenceType#locationsOfLine} answers that, and the two disagree in at
 * least one real case: when the compiler emits two entries at one bytecode offset, the second
 * replaces the first and the replaced line stops existing for a debugger while {@code javap} still
 * lists it.
 *
 * <p>That defect cost four rounds of investigation, all of it reading class files and finding
 * nothing, because the class files genuinely said the failing and working lines were identical.
 * This settles the same question in one run. Reach for it before inspecting bytecode, not after.
 *
 * <h2>Use</h2>
 *
 * <pre>{@code
 * # 1. start the program suspended, so nothing has loaded when the probe attaches
 * JAVA_TOOL_OPTIONS='-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5099' \
 *   ./scripts/flix-fork run --Xdebug --yes &
 *
 * # 2. ask it
 * java scripts/FlixLineProbe.java 5099 Main.flix 101 107
 * }</pre>
 *
 * <p>Single-file source execution, so there is nothing to build and no Gradle task to learn.
 * Requires a JDK, which the project needs anyway.
 *
 * <p>A line reported {@code UNADDRESSABLE} is present in the line table but invisible to the
 * debugger -- a compiler problem. A line reported {@code absent} was never emitted, which usually
 * means {@code --Xdebug} did not reach the compiler.
 */
public final class FlixLineProbe {

    private static final long ATTACH_TIMEOUT_MS = 120_000;

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: FlixLineProbe <jdwp-port> <source-file-name> <first-line> <last-line>");
            System.exit(2);
        }
        String port = args[0];
        String sourceName = args[1];
        int first = Integer.parseInt(args[2]);
        int last = Integer.parseInt(args[3]);

        VirtualMachine vm = attach(port);

        // Watching every class rather than a name pattern: Flix class names encode the definition
        // and carry a counter that changes between builds, so any name written down is stale as
        // soon as the project is recompiled.
        ClassPrepareRequest prepare = vm.eventRequestManager().createClassPrepareRequest();
        prepare.enable();
        vm.resume();

        Map<Integer, Integer> inTable = new TreeMap<>();
        Map<Integer, Integer> addressable = new TreeMap<>();
        for (int line = first; line <= last; line++) {
            inTable.put(line, 0);
            addressable.put(line, 0);
        }
        int scanned = scan(vm, sourceName, first, last, inTable, addressable);

        System.out.printf("classes compiled from %s that prepared: %d%n%n", sourceName, scanned);
        System.out.printf("%-6s %-16s %-16s %s%n", "LINE", "IN LINE TABLE", "ADDRESSABLE", "VERDICT");
        boolean anyUnaddressable = false;
        for (int line = first; line <= last; line++) {
            int present = inTable.get(line);
            int bindable = addressable.get(line);
            String verdict;
            if (bindable > 0) {
                verdict = "can bind";
            } else if (present > 0) {
                verdict = "UNADDRESSABLE -- in the table, invisible to the debugger";
                anyUnaddressable = true;
            } else {
                verdict = "absent -- was --Xdebug passed to the compiler?";
            }
            System.out.printf("%-6d %-16d %-16d %s%n", line, present, bindable, verdict);
        }

        try {
            vm.dispose();
        } catch (Exception ignored) {
            // The debuggee may already have exited; the report above is what matters.
        }
        // Non-zero when a line is present but unbindable, so this can gate a script.
        System.exit(anyUnaddressable ? 1 : 0);
    }

    private static VirtualMachine attach(String port) throws Exception {
        AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
                .filter(c -> c.name().equals("com.sun.jdi.SocketAttach"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no socket-attach connector available"));
        Map<String, Connector.Argument> params = connector.defaultArguments();
        params.get("hostname").setValue("localhost");
        params.get("port").setValue(port);
        return connector.attach(params);
    }

    /** Walks class-prepare events until the debuggee exits, tallying what each class can host. */
    private static int scan(
            VirtualMachine vm,
            String sourceName,
            int first,
            int last,
            Map<Integer, Integer> inTable,
            Map<Integer, Integer> addressable) {
        int scanned = 0;
        EventQueue queue = vm.eventQueue();
        long deadline = System.currentTimeMillis() + ATTACH_TIMEOUT_MS;
        boolean alive = true;

        while (alive && System.currentTimeMillis() < deadline) {
            EventSet set;
            try {
                set = queue.remove(2000);
            } catch (Exception e) {
                break;
            }
            if (set == null) {
                continue;
            }
            for (Event event : set) {
                if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
                    alive = false;
                    continue;
                }
                if (!(event instanceof ClassPrepareEvent prepared)) {
                    continue;
                }
                ReferenceType type = prepared.referenceType();
                if (!tally(type, sourceName, first, last, inTable, addressable)) {
                    continue;
                }
                scanned++;
            }
            if (alive) {
                try {
                    set.resume();
                } catch (Exception ignored) {
                    // Racing VM death; the next queue read ends the loop.
                }
            }
        }
        return scanned;
    }

    /** Records what {@code type} contributes, or {@code false} if it is not from this source. */
    private static boolean tally(
            ReferenceType type,
            String sourceName,
            int first,
            int last,
            Map<Integer, Integer> inTable,
            Map<Integer, Integer> addressable) {
        String stratum = type.availableStrata().contains("Flix") ? "Flix" : type.defaultStratum();
        String matched;
        try {
            matched = type.sourceNames(stratum).stream()
                    .filter(name -> name.endsWith(sourceName))
                    .findFirst()
                    .orElse(null);
        } catch (AbsentInformationException e) {
            return false;
        }
        if (matched == null) {
            return false;
        }

        Set<Integer> lines = new HashSet<>();
        try {
            for (Location location : type.allLineLocations(stratum, matched)) {
                lines.add(location.lineNumber(stratum));
            }
        } catch (AbsentInformationException ignored) {
            // A class with no line information contributes nothing but is still from this source.
        }

        for (int line = first; line <= last; line++) {
            if (lines.contains(line)) {
                inTable.merge(line, 1, Integer::sum);
            }
            try {
                List<Location> at = type.locationsOfLine(stratum, matched, line);
                if (!at.isEmpty()) {
                    addressable.merge(line, 1, Integer::sum);
                }
            } catch (Exception ignored) {
                // Absent information for this line: not addressable, which is what the tally means.
            }
        }
        return true;
    }
}
