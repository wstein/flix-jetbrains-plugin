import com.sun.jdi.*;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.*;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.StepRequest;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal Debug Adapter Protocol (DAP) server that debugs a running Flix
 * program by attaching to its JVM over JDWP and resolving breakpoints
 * against .flix source lines via the "Flix" stratum that a fork of Flix
 * (github.com/wstein/flix-fork, --Xdebug) embeds as a JSR-45
 * SourceDebugExtension.
 *
 * This intentionally supports only the handful of requests needed to set a
 * line breakpoint, hit it, and inspect the call stack/locals -- it is not a
 * general-purpose Java debugger.
 */
public class FlixDebugAdapter {

    /**
     * VS Code (and the standalone {@code dap_client_test.py}) launch this adapter as a child
     * process and speak DAP directly over its stdin/stdout, so that stays the default with no
     * arguments. IntelliJ's LSP4IJ, by contrast, has no stdio DAP transport at all -- its
     * DebugAdapterDescriptor/ServerReadyConfig machinery only ever connects to a DAP server over
     * a TCP socket (confirmed by reading LSP4IJ's own source, not just its docs) -- so "--port
     * <n>" opens a one-shot server socket on that exact port instead and speaks DAP there once a
     * single client connects. Both modes share every line of protocol/JDI handling below.
     */
    public static void main(String[] args) throws Exception {
        Integer port = null;
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            }
        }
        if (port != null) {
            runOverSocket(port);
        } else {
            new FlixDebugAdapter(System.in, System.out).run();
        }
    }

    private static void runOverSocket(int port) throws Exception {
        try (ServerSocket server = new ServerSocket(port)) {
            // LSP4IJ's DebugAdapterDescriptor picks this exact port itself (via its "${port}"
            // command-line substitution) before launching us, so it already knows where to
            // connect; this line is only a human-visible/log-matchable readiness signal, not
            // something the port number is parsed out of.
            System.out.println("Listening for DAP client on port " + port);
            System.out.flush();
            Socket client = server.accept();
            new FlixDebugAdapter(client.getInputStream(), client.getOutputStream()).run();
        }
    }

    // ------------------------------------------------------------------
    // DAP transport
    // ------------------------------------------------------------------

    private final InputStream in;
    private final PrintStream out;
    private final AtomicInteger seq = new AtomicInteger(1);

    FlixDebugAdapter(InputStream in, OutputStream out) throws UnsupportedEncodingException {
        this.in = in;
        this.out = new PrintStream(out, true, "UTF-8");
    }

    private void run() throws IOException {
        while (true) {
            Map<String, Object> msg = readMessage();
            if (msg == null) return;
            String type = (String) msg.get("type");
            if ("request".equals(type)) {
                handleRequest(msg);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMessage() throws IOException {
        int contentLength = -1;
        while (true) {
            String line = readHeaderLine();
            if (line == null) return null;
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon > 0) {
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if (name.equalsIgnoreCase("Content-Length")) {
                    contentLength = Integer.parseInt(value);
                }
            }
        }
        if (contentLength < 0) return null;
        byte[] body = new byte[contentLength];
        int read = 0;
        while (read < contentLength) {
            int n = in.read(body, read, contentLength - read);
            if (n < 0) return null;
            read += n;
        }
        String json = new String(body, StandardCharsets.UTF_8);
        Object parsed = Json.parse(json);
        return (Map<String, Object>) parsed;
    }

    private String readHeaderLine() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int prev = -1;
        int c;
        while ((c = in.read()) != -1) {
            if (prev == '\r' && c == '\n') {
                byte[] bytes = buf.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
            }
            buf.write(c);
            prev = c;
        }
        return buf.size() == 0 ? null : buf.toString(StandardCharsets.UTF_8);
    }

    private synchronized void send(Map<String, Object> msg) {
        msg.put("seq", seq.getAndIncrement());
        String json = Json.write(msg);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        out.print("Content-Length: " + bytes.length + "\r\n\r\n");
        out.write(bytes, 0, bytes.length);
        out.flush();
    }

    private void sendResponse(Map<String, Object> request, boolean success, Object body) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("type", "response");
        resp.put("request_seq", request.get("seq"));
        resp.put("success", success);
        resp.put("command", request.get("command"));
        if (body != null) resp.put("body", body);
        send(resp);
    }

    private void sendErrorResponse(Map<String, Object> request, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", Map.of("id", 1, "format", message));
        sendResponse(request, false, body);
    }

    private void sendEvent(String event, Object body) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "event");
        msg.put("event", event);
        if (body != null) msg.put("body", body);
        send(msg);
    }

    // ------------------------------------------------------------------
    // Request dispatch
    // ------------------------------------------------------------------

    private VirtualMachine vm;
    private Thread eventThread;

    /** Namespaces with no user code in this project -- excluded both from class-prepare watching
     * (onAttach) and from stepping (onStep), so e.g. java.lang.invoke.LambdaForm$DMH synthetic
     * frames (generated for invokedynamic call sites, which Flix's compiled lambdas use heavily)
     * are stepped over instead of surfacing as a source-less frame VS Code can't display. */
    private static final String[] EXCLUDED_PACKAGES = {
            "java.*", "javax.*", "jdk.*", "sun.*", "com.sun.*", "scala.*",
            "dev.flix.runtime.*", "ca.uwaterloo.*", "com.*", "org.*", "net.*"};

    @SuppressWarnings("unchecked")
    private void handleRequest(Map<String, Object> req) {
        String command = (String) req.get("command");
        Map<String, Object> args = (Map<String, Object>) req.getOrDefault("arguments", Map.of());
        try {
            switch (command) {
                case "initialize" -> onInitialize(req);
                // "launch" is handled identically to "attach": this adapter never launches a
                // program itself, it only ever attaches to an already-running --Xdebug JVM's
                // JDWP port given in `arguments`. A DAP client still gets to pick which of the
                // two verbs it sends us for its own reasons -- e.g. LSP4IJ's DAPClient chooses
                // launch vs. attach based solely on whether *it* had to spawn this adapter as a
                // process (which it always does for us), not on what this adapter does upon
                // receiving the request.
                case "attach", "launch" -> onAttach(req, args);
                case "setBreakpoints" -> onSetBreakpoints(req, args);
                case "configurationDone" -> onConfigurationDone(req);
                case "threads" -> onThreads(req);
                case "stackTrace" -> onStackTrace(req, args);
                case "scopes" -> onScopes(req, args);
                case "variables" -> onVariables(req, args);
                case "evaluate" -> onEvaluate(req, args);
                case "continue" -> onContinue(req);
                case "next" -> onStep(req, args, StepRequest.STEP_OVER);
                case "stepIn" -> onStep(req, args, StepRequest.STEP_INTO);
                case "stepOut" -> onStep(req, args, StepRequest.STEP_OUT);
                case "pause" -> onPause(req);
                case "disconnect" -> onDisconnect(req);
                default -> sendResponse(req, true, null);
            }
        } catch (Exception e) {
            sendErrorResponse(req, command + " failed: " + e);
        }
    }

    private void onInitialize(Map<String, Object> req) {
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("supportsConfigurationDoneRequest", true);
        sendResponse(req, true, caps);
        sendEvent("initialized", null);
    }

    // ------------------------------------------------------------------
    // Attach
    // ------------------------------------------------------------------

    private void onAttach(Map<String, Object> req, Map<String, Object> args) throws Exception {
        String host = String.valueOf(args.getOrDefault("hostName", "localhost"));
        int port = ((Number) args.getOrDefault("port", 5005)).intValue();

        AttachingConnector connector = null;
        for (Connector c : Bootstrap.virtualMachineManager().attachingConnectors()) {
            if (c.transport() != null && "dt_socket".equals(c.transport().name())) {
                connector = (AttachingConnector) c;
                break;
            }
        }
        if (connector == null) throw new IllegalStateException("no dt_socket attaching connector available");

        Map<String, Connector.Argument> cargs = connector.defaultArguments();
        cargs.get("hostname").setValue(host);
        cargs.get("port").setValue(String.valueOf(port));
        vm = connector.attach(cargs);

        // Flix's own generated classes (Def$foo, Clo$bar, ...) are unqualified (no package), so
        // excluding these known-irrelevant namespaces leaves essentially only user code visible.
        // Without this, watching every class the whole JVM loads (including Flix's own Scala
        // compiler/runtime) makes startup extremely slow under SUSPEND_ALL.
        EventRequestManager erm = vm.eventRequestManager();
        ClassPrepareRequest cpr = erm.createClassPrepareRequest();
        for (String pattern : EXCLUDED_PACKAGES) {
            cpr.addClassExclusionFilter(pattern);
        }
        cpr.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        cpr.enable();

        eventThread = new Thread(this::eventLoop, "flix-debug-jdi-events");
        eventThread.setDaemon(true);
        eventThread.start();

        sendResponse(req, true, null);
    }

    private void onConfigurationDone(Map<String, Object> req) {
        sendResponse(req, true, null);
        // The target JVM is suspended at startup (JDWP suspend=y) so that
        // breakpoints set here bind before any user code runs. Resume it
        // now that configuration (breakpoints, etc.) is done.
        if (vm != null) vm.resume();
    }

    // ------------------------------------------------------------------
    // Breakpoints
    // ------------------------------------------------------------------

    /** A breakpoint requested for a source file, not yet necessarily resolved to a JDI location. */
    private static final class PendingBreakpoint {
        final int id;
        final int line;
        boolean verified;
        PendingBreakpoint(int id, int line) { this.id = id; this.line = line; }
    }

    /** file path (as given by the client) -> requested lines, replaced wholesale on each setBreakpoints call. */
    private final Map<String, List<PendingBreakpoint>> pendingByPath = new LinkedHashMap<>();
    /** file path -> the BreakpointRequests we created for it, so a later setBreakpoints call can clear them. */
    private final Map<String, List<BreakpointRequest>> requestsByPath = new LinkedHashMap<>();
    private int nextBreakpointId = 1;

    @SuppressWarnings("unchecked")
    private void onSetBreakpoints(Map<String, Object> req, Map<String, Object> args) {
        Map<String, Object> source = (Map<String, Object>) args.get("source");
        String path = String.valueOf(source.get("path"));
        List<Map<String, Object>> breakpoints = (List<Map<String, Object>>) args.getOrDefault("breakpoints", List.of());

        EventRequestManager erm = vm.eventRequestManager();
        for (BreakpointRequest r : requestsByPath.getOrDefault(path, List.of())) {
            erm.deleteEventRequest(r);
        }
        requestsByPath.put(path, new ArrayList<>());

        List<PendingBreakpoint> pending = new ArrayList<>();
        List<Object> resultBreakpoints = new ArrayList<>();
        for (Map<String, Object> bp : breakpoints) {
            int line = ((Number) bp.get("line")).intValue();
            pending.add(new PendingBreakpoint(nextBreakpointId++, line));
        }
        pendingByPath.put(path, pending);

        boolean[] resolved = new boolean[pending.size()];
        if (vm != null) {
            for (ReferenceType rt : vm.allClasses()) {
                resolveBreakpointsIn(rt, path, pending, resolved, false);
            }
        }
        for (int i = 0; i < pending.size(); i++) {
            pending.get(i).verified = resolved[i];
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("id", pending.get(i).id);
            b.put("verified", resolved[i]);
            b.put("line", pending.get(i).line);
            resultBreakpoints.add(b);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("breakpoints", resultBreakpoints);
        sendResponse(req, true, body);
    }

    /**
     * Tries to resolve as many of `pending`'s not-yet-resolved lines as possible against `rt`,
     * emitting a "breakpoint" (reason: "changed") event for any that newly verify -- e.g. when
     * called from a class-prepare handler after the initial setBreakpoints response already
     * reported them as unverified.
     */
    private void resolveBreakpointsIn(ReferenceType rt, String path, List<PendingBreakpoint> pending, boolean[] resolved, boolean emitEvents) {
        String baseName = basename(path);
        List<String> strata;
        try {
            strata = rt.availableStrata();
        } catch (Exception e) {
            return;
        }
        List<String> tryStrata = new ArrayList<>();
        if (strata.contains("Flix")) tryStrata.add("Flix");
        tryStrata.add(rt.defaultStratum());

        for (String stratum : tryStrata) {
            List<String> sourceNames;
            try {
                sourceNames = rt.sourceNames(stratum);
            } catch (AbsentInformationException e) {
                continue;
            }
            boolean matches = false;
            for (String name : sourceNames) {
                if (name.equals(baseName) || path.endsWith(name)) { matches = true; break; }
            }
            if (!matches) continue;

            for (int i = 0; i < pending.size(); i++) {
                if (resolved[i]) continue;
                int line = pending.get(i).line;
                List<Location> locs;
                try {
                    locs = rt.locationsOfLine(stratum, baseName, line);
                } catch (AbsentInformationException e) {
                    continue;
                }
                if (locs.isEmpty()) continue;
                EventRequestManager erm = vm.eventRequestManager();
                for (Location loc : locs) {
                    BreakpointRequest br = erm.createBreakpointRequest(loc);
                    br.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                    br.enable();
                    requestsByPath.computeIfAbsent(path, k -> new ArrayList<>()).add(br);
                    debug("breakpoint set at " + loc);
                }
                resolved[i] = true;
                if (emitEvents) {
                    PendingBreakpoint pb = pending.get(i);
                    pb.verified = true;
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("id", pb.id);
                    b.put("verified", true);
                    b.put("line", pb.line);
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("reason", "changed");
                    body.put("breakpoint", b);
                    sendEvent("breakpoint", body);
                }
            }
            return;
        }
    }

    private static String basename(String path) {
        int idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return idx < 0 ? path : path.substring(idx + 1);
    }

    // ------------------------------------------------------------------
    // Threads / stack / scopes / variables
    // ------------------------------------------------------------------

    private void onThreads(Map<String, Object> req) {
        List<Object> threads = new ArrayList<>();
        if (vm != null) {
            for (ThreadReference t : vm.allThreads()) {
                Map<String, Object> jt = new LinkedHashMap<>();
                jt.put("id", (int) t.uniqueID());
                jt.put("name", t.name());
                threads.add(jt);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("threads", threads);
        sendResponse(req, true, body);
    }

    /** synthetic frameId -> (threadId, frame index), since JDI StackFrame objects go stale across resumes. */
    private final Map<Integer, int[]> frameById = new LinkedHashMap<>();
    /** synthetic variablesReference -> the JDI ObjectReference/ArrayReference it should expand to. */
    private final Map<Integer, ObjectReference> objectRefById = new LinkedHashMap<>();
    /** frameById, objectRefById and StackFrame/ObjectReference identities all go stale once the VM resumes, so
     * frame ids and object ids share one counter/generation and are cleared together. */
    private int nextRefId = 1;

    private ThreadReference threadById(long id) {
        for (ThreadReference t : vm.allThreads()) {
            if (t.uniqueID() == id) return t;
        }
        return null;
    }

    private void onStackTrace(Map<String, Object> req, Map<String, Object> args) throws IncompatibleThreadStateException {
        long threadId = ((Number) args.get("threadId")).longValue();
        ThreadReference thread = threadById(threadId);
        List<Object> frames = new ArrayList<>();
        if (thread != null) {
            List<StackFrame> stack = thread.frames();
            for (int i = 0; i < stack.size(); i++) {
                StackFrame sf = stack.get(i);
                Location loc = sf.location();
                int frameId = nextRefId++;
                frameById.put(frameId, new int[]{(int) threadId, i});

                String stratum = loc.declaringType().availableStrata().contains("Flix") ? "Flix" : loc.declaringType().defaultStratum();
                String sourcePath;
                int line;
                try {
                    sourcePath = loc.sourcePath(stratum);
                    line = loc.lineNumber(stratum);
                } catch (AbsentInformationException e) {
                    sourcePath = loc.declaringType().name();
                    line = loc.lineNumber();
                }

                Map<String, Object> frame = new LinkedHashMap<>();
                frame.put("id", frameId);
                frame.put("name", loc.method().name());
                Map<String, Object> source = new LinkedHashMap<>();
                source.put("name", basename(sourcePath));
                source.put("path", sourcePath);
                frame.put("source", source);
                frame.put("line", line);
                frame.put("column", 1);
                frames.add(frame);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stackFrames", frames);
        body.put("totalFrames", frames.size());
        sendResponse(req, true, body);
    }

    private void onScopes(Map<String, Object> req, Map<String, Object> args) {
        int frameId = ((Number) args.get("frameId")).intValue();
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("name", "Locals");
        scope.put("variablesReference", frameId);
        scope.put("expensive", false);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scopes", List.of(scope));
        sendResponse(req, true, body);
    }

    private void onVariables(Map<String, Object> req, Map<String, Object> args) throws IncompatibleThreadStateException {
        int variablesReference = ((Number) args.get("variablesReference")).intValue();
        List<Object> vars = new ArrayList<>();
        int[] loc = frameById.get(variablesReference);
        if (loc != null) {
            ThreadReference thread = threadById(loc[0]);
            if (thread != null && loc[1] < thread.frameCount()) {
                StackFrame sf = thread.frame(loc[1]);
                try {
                    for (LocalVariable lv : sf.visibleVariables()) {
                        vars.add(toVariable(lv.name(), sf.getValue(lv), lv.typeName()));
                    }
                } catch (AbsentInformationException e) {
                    // no local variable table for this frame; nothing to show.
                }
            }
        } else {
            ObjectReference or = objectRefById.get(variablesReference);
            if (or instanceof ArrayReference ar) {
                vars.addAll(arrayElementVariables(ar));
            } else if (or != null) {
                vars.addAll(fieldVariables(or));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("variables", vars);
        sendResponse(req, true, body);
    }

    /** Supports a dotted-path expression against the given frame's locals, e.g. "classes" or
     * "classes.v0" -- enough for watches/hover to drill into a value without needing a real
     * expression language (no arithmetic, calls, or indexing). Requires a frameId; this adapter
     * has no notion of a global/REPL scope to evaluate against. */
    private void onEvaluate(Map<String, Object> req, Map<String, Object> args) throws IncompatibleThreadStateException {
        Object frameIdObj = args.get("frameId");
        if (frameIdObj == null) {
            sendErrorResponse(req, "evaluate requires a frameId; no global scope is supported");
            return;
        }
        int[] loc = frameById.get(((Number) frameIdObj).intValue());
        if (loc == null) {
            sendErrorResponse(req, "unknown frameId");
            return;
        }
        ThreadReference thread = threadById(loc[0]);
        if (thread == null || loc[1] >= thread.frameCount()) {
            sendErrorResponse(req, "stack frame no longer available");
            return;
        }
        StackFrame sf = thread.frame(loc[1]);
        String expression = String.valueOf(args.get("expression")).trim();
        String[] segments = expression.split("\\.");

        Value current;
        try {
            LocalVariable lv = null;
            for (LocalVariable candidate : sf.visibleVariables()) {
                if (candidate.name().equals(segments[0])) {
                    lv = candidate;
                    break;
                }
            }
            if (lv == null) {
                sendErrorResponse(req, "no such variable: " + segments[0]);
                return;
            }
            current = sf.getValue(lv);
        } catch (AbsentInformationException e) {
            sendErrorResponse(req, "no local variable information for this frame");
            return;
        }

        for (int i = 1; i < segments.length; i++) {
            if (!(current instanceof ObjectReference or)) {
                sendErrorResponse(req, "cannot access field '" + segments[i] + "' on a non-object value");
                return;
            }
            Field f = or.referenceType().fieldByName(segments[i]);
            if (f == null) {
                sendErrorResponse(req, "no such field: " + segments[i]);
                return;
            }
            current = or.getValue(f);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("result", formatValue(current));
        body.put("type", current == null ? "null" : current.type().name());
        body.put("variablesReference", registerRef(current));
        sendResponse(req, true, body);
    }

    /** Every field (including inherited) of an arbitrary object, resolved generically via JDI reflection
     * so that Flix's compiled representations (Tag/Obj wrappers, records, ...) drill down without any
     * Flix-specific knowledge here. */
    private List<Object> fieldVariables(ObjectReference or) {
        List<Object> vars = new ArrayList<>();
        for (Field f : or.referenceType().allFields()) {
            if (f.isStatic()) continue;
            Value v;
            try {
                v = or.getValue(f);
            } catch (Exception e) {
                continue;
            }
            vars.add(toVariable(f.name(), v, f.typeName()));
        }
        return vars;
    }

    private List<Object> arrayElementVariables(ArrayReference ar) {
        List<Object> vars = new ArrayList<>();
        List<Value> values = ar.getValues();
        for (int i = 0; i < values.size(); i++) {
            vars.add(toVariable("[" + i + "]", values.get(i), ""));
        }
        return vars;
    }

    private Map<String, Object> toVariable(String name, Value v, String typeName) {
        Map<String, Object> var = new LinkedHashMap<>();
        var.put("name", name);
        var.put("value", formatValue(v));
        var.put("type", typeName);
        var.put("variablesReference", registerRef(v));
        return var;
    }

    /** Registers an ObjectReference/ArrayReference so a later "variables" request against the returned
     * id can expand it; returns 0 (DAP's "no children") for primitives, strings and null. */
    private int registerRef(Value v) {
        if (!(v instanceof ObjectReference or) || v instanceof StringReference) return 0;
        int id = nextRefId++;
        objectRefById.put(id, or);
        return id;
    }

    private static String formatValue(Value v) {
        if (v == null) return "null";
        if (v instanceof StringReference sr) return "\"" + sr.value() + "\"";
        if (v instanceof ArrayReference ar) {
            return ar.referenceType().name() + "[" + ar.length() + "]";
        }
        if (v instanceof ObjectReference or) {
            ReferenceType rt = or.referenceType();
            if (hasRecordShape(rt)) {
                return formatRecord(or);
            }
            // Flix's tagged-union cases (Tag$Obj$Obj and friends) always carry an "ordinal" field
            // discriminating which case of the enum this is. Surfacing it is a safe, honest hint --
            // unlike guessing the case's *name* or treating e.g. a 2-arg case as a list, which would
            // require compiler metadata this adapter doesn't have and could show something actively
            // wrong for a user-defined enum that happens to share List's v0/v1 shape.
            Field ordinalField = rt.fieldByName("ordinal");
            if (ordinalField != null) {
                try {
                    return rt.name() + "@" + or.uniqueID() + " (ordinal=" + or.getValue(ordinalField) + ")";
                } catch (Exception e) {
                    // fall through to the plain form below
                }
            }
            return rt.name() + "@" + or.uniqueID();
        }
        return v.toString();
    }

    private static boolean hasRecordShape(ReferenceType rt) {
        return rt.fieldByName("label") != null && rt.fieldByName("value") != null && rt.fieldByName("rest") != null;
    }

    /** Flix compiles structural records as a chain of "label"/"value"/"rest" cells terminating in
     * some non-matching sentinel object; walking that chain into a normal {label = value, ...}
     * summary is a safe, generic structural transformation (not a guess about a specific record
     * type's meaning) since every Flix record uses this exact three-field encoding regardless of
     * its fields' names or types. */
    private static String formatRecord(ObjectReference or) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        ObjectReference cur = or;
        int depth = 0;
        while (cur != null && depth++ < 64) {
            ReferenceType rt = cur.referenceType();
            Field labelField = rt.fieldByName("label");
            Field valueField = rt.fieldByName("value");
            Field restField = rt.fieldByName("rest");
            if (labelField == null || valueField == null || restField == null) break;
            Value labelValue;
            try {
                labelValue = cur.getValue(labelField);
            } catch (Exception e) {
                break;
            }
            if (!(labelValue instanceof StringReference labelStr)) break;
            if (!first) sb.append(", ");
            first = false;
            sb.append(labelStr.value()).append(" = ");
            try {
                sb.append(formatValue(cur.getValue(valueField)));
            } catch (Exception e) {
                sb.append("?");
            }
            Value restValue;
            try {
                restValue = cur.getValue(restField);
            } catch (Exception e) {
                break;
            }
            cur = restValue instanceof ObjectReference nextOr ? nextOr : null;
        }
        sb.append("}");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Execution control
    // ------------------------------------------------------------------

    private void onContinue(Map<String, Object> req) {
        frameById.clear();
        objectRefById.clear();
        vm.resume();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("allThreadsContinued", true);
        sendResponse(req, true, body);
    }

    private void onStep(Map<String, Object> req, Map<String, Object> args, int depth) {
        long threadId = ((Number) args.get("threadId")).longValue();
        ThreadReference thread = threadById(threadId);
        if (thread == null) { sendResponse(req, true, null); return; }
        EventRequestManager erm = vm.eventRequestManager();
        StepRequest step = erm.createStepRequest(thread, StepRequest.STEP_LINE, depth);
        for (String pattern : EXCLUDED_PACKAGES) {
            step.addClassExclusionFilter(pattern);
        }
        step.addCountFilter(1);
        step.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        step.enable();
        frameById.clear();
        objectRefById.clear();
        sendResponse(req, true, null);
        vm.resume();
    }

    private void onPause(Map<String, Object> req) {
        if (vm != null) vm.suspend();
        sendResponse(req, true, null);
        if (vm != null && !vm.allThreads().isEmpty()) {
            sendStoppedEvent(vm.allThreads().get(0), "pause");
        }
    }

    private void onDisconnect(Map<String, Object> req) {
        try {
            if (vm != null) vm.dispose();
        } catch (Exception ignored) {
        }
        sendResponse(req, true, null);
        System.exit(0);
    }

    private void sendStoppedEvent(ThreadReference thread, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        body.put("threadId", (int) thread.uniqueID());
        body.put("allThreadsStopped", true);
        sendEvent("stopped", body);
    }

    // ------------------------------------------------------------------
    // JDI event loop
    // ------------------------------------------------------------------

    private void eventLoop() {
        try {
            EventQueue queue = vm.eventQueue();
            while (true) {
                EventSet set = queue.remove();
                boolean keepRunning = true;
                for (Event e : set) {
                    try {
                        if (e instanceof ClassPrepareEvent cpe) {
                            onClassPrepare(cpe.referenceType());
                        } else if (e instanceof BreakpointEvent be) {
                            debug("BreakpointEvent at " + be.location());
                            sendStoppedEvent(be.thread(), "breakpoint");
                            keepRunning = false;
                        } else if (e instanceof StepEvent se) {
                            vm.eventRequestManager().deleteEventRequest(se.request());
                            sendStoppedEvent(se.thread(), "step");
                            keepRunning = false;
                        } else if (e instanceof VMDeathEvent || e instanceof VMDisconnectEvent) {
                            sendEvent("terminated", null);
                            return;
                        }
                    } catch (Throwable t) {
                        debug("error handling event " + e + ": " + t);
                        t.printStackTrace(System.err);
                    }
                }
                if (keepRunning) set.resume();
                // otherwise: leave the event set (and its threads) suspended until `continue`/step resumes them.
            }
        } catch (VMDisconnectedException e) {
            sendEvent("terminated", null);
        } catch (InterruptedException ignored) {
        } catch (Throwable t) {
            debug("event loop died: " + t);
            t.printStackTrace(System.err);
        }
    }

    private static void debug(String msg) {
        System.err.println("[flix-debug-adapter] " + msg);
    }

    private void onClassPrepare(ReferenceType rt) {
        for (Map.Entry<String, List<PendingBreakpoint>> entry : pendingByPath.entrySet()) {
            String path = entry.getKey();
            List<PendingBreakpoint> pending = entry.getValue();
            boolean[] resolved = new boolean[pending.size()];
            // Re-check which lines already have a live BreakpointRequest so we don't duplicate them.
            List<BreakpointRequest> existing = requestsByPath.getOrDefault(path, List.of());
            for (int i = 0; i < pending.size(); i++) {
                int line = pending.get(i).line;
                for (BreakpointRequest r : existing) {
                    if (lineOf(r) == line) { resolved[i] = true; break; }
                }
            }
            resolveBreakpointsIn(rt, path, pending, resolved, true);
        }
    }

    private static int lineOf(BreakpointRequest r) {
        Location loc = r.location();
        try {
            return loc.lineNumber(loc.declaringType().defaultStratum());
        } catch (Exception e) {
            return loc.lineNumber();
        }
    }

    // ------------------------------------------------------------------
    // Minimal JSON (only what DAP messages need: objects, arrays, strings,
    // numbers, booleans, null).
    // ------------------------------------------------------------------

    static final class Json {
        static Object parse(String s) {
            return new Parser(s).parseValue();
        }

        static String write(Object o) {
            StringBuilder sb = new StringBuilder();
            writeValue(o, sb);
            return sb.toString();
        }

        private static void writeValue(Object o, StringBuilder sb) {
            if (o == null) {
                sb.append("null");
            } else if (o instanceof String s) {
                writeString(s, sb);
            } else if (o instanceof Boolean b) {
                sb.append(b.toString());
            } else if (o instanceof Number n) {
                if (n instanceof Double || n instanceof Float) sb.append(n.toString());
                else sb.append(n.longValue());
            } else if (o instanceof Map<?, ?> m) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    writeString(String.valueOf(e.getKey()), sb);
                    sb.append(':');
                    writeValue(e.getValue(), sb);
                }
                sb.append('}');
            } else if (o instanceof List<?> l) {
                sb.append('[');
                boolean first = true;
                for (Object v : l) {
                    if (!first) sb.append(',');
                    first = false;
                    writeValue(v, sb);
                }
                sb.append(']');
            } else {
                writeString(o.toString(), sb);
            }
        }

        private static void writeString(String s, StringBuilder sb) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                    }
                }
            }
            sb.append('"');
        }

        private static final class Parser {
            private final String s;
            private int i = 0;

            Parser(String s) { this.s = s; }

            Object parseValue() {
                skipWs();
                char c = s.charAt(i);
                return switch (c) {
                    case '{' -> parseObject();
                    case '[' -> parseArray();
                    case '"' -> parseString();
                    case 't' -> { i += 4; yield Boolean.TRUE; }
                    case 'f' -> { i += 5; yield Boolean.FALSE; }
                    case 'n' -> { i += 4; yield null; }
                    default -> parseNumber();
                };
            }

            private Map<String, Object> parseObject() {
                Map<String, Object> m = new LinkedHashMap<>();
                i++; // {
                skipWs();
                if (peek() == '}') { i++; return m; }
                while (true) {
                    skipWs();
                    String key = parseString();
                    skipWs();
                    i++; // :
                    Object value = parseValue();
                    m.put(key, value);
                    skipWs();
                    if (peek() == ',') { i++; continue; }
                    if (peek() == '}') { i++; break; }
                    throw new IllegalArgumentException("malformed object at " + i);
                }
                return m;
            }

            private List<Object> parseArray() {
                List<Object> l = new ArrayList<>();
                i++; // [
                skipWs();
                if (peek() == ']') { i++; return l; }
                while (true) {
                    l.add(parseValue());
                    skipWs();
                    if (peek() == ',') { i++; continue; }
                    if (peek() == ']') { i++; break; }
                    throw new IllegalArgumentException("malformed array at " + i);
                }
                return l;
            }

            private String parseString() {
                i++; // opening quote
                StringBuilder sb = new StringBuilder();
                while (true) {
                    char c = s.charAt(i++);
                    if (c == '"') break;
                    if (c == '\\') {
                        char e = s.charAt(i++);
                        switch (e) {
                            case '"' -> sb.append('"');
                            case '\\' -> sb.append('\\');
                            case '/' -> sb.append('/');
                            case 'n' -> sb.append('\n');
                            case 'r' -> sb.append('\r');
                            case 't' -> sb.append('\t');
                            case 'b' -> sb.append('\b');
                            case 'f' -> sb.append('\f');
                            case 'u' -> {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                i += 4;
                            }
                            default -> sb.append(e);
                        }
                    } else {
                        sb.append(c);
                    }
                }
                return sb.toString();
            }

            private Object parseNumber() {
                int start = i;
                while (i < s.length() && "-+.0123456789eE".indexOf(s.charAt(i)) >= 0) i++;
                String num = s.substring(start, i);
                if (num.contains(".") || num.contains("e") || num.contains("E")) return Double.parseDouble(num);
                return Long.parseLong(num);
            }

            private char peek() { return s.charAt(i); }

            private void skipWs() {
                while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
            }
        }
    }
}
