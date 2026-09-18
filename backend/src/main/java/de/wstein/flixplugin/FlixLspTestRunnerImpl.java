package de.wstein.flixplugin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.KillableColoredProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.redhat.devtools.lsp4ij.LanguageServerItem;
import com.redhat.devtools.lsp4ij.LanguageServerManager;
import org.flixlang.intellij.run.FlixLspTestRunner;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Runs tests over LSP and falls back to the existing CLI process before any LSP run is accepted. */
public final class FlixLspTestRunnerImpl implements FlixLspTestRunner {
    private static final Gson GSON = new Gson();
    private static final long SERVER_TIMEOUT_SECONDS = 2;

    private final Project project;
    private final ConcurrentHashMap<String, LspTestProcessHandler> runs = new ConcurrentHashMap<>();

    public FlixLspTestRunnerImpl(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @NotNull ProcessHandler createProcess(
            @NotNull List<String> filters,
            @NotNull List<String> fallbackCommand,
            @NotNull Path workingDirectory) {
        return new LspTestProcessHandler(this, filters, fallbackCommand, workingDirectory);
    }

    void accept(@Nullable FlixTestRunEvent event) {
        if (event == null || event.getRunId() == null) return;
        LspTestProcessHandler handler = runs.get(event.getRunId());
        if (handler != null) handler.accept(event);
    }

    /** Converts an LSP event to the compiler JSONL shape consumed by the existing SM converter. */
    static JsonObject jsonLine(FlixTestRunEvent event) {
        JsonObject result = new JsonObject();
        result.addProperty("event", event.getEvent());
        result.addProperty("protocolVersion", event.getProtocolVersion());
        result.addProperty("nanos", event.getNanos());
        if (event.getTests() != null && !event.getTests().isEmpty()) {
            result.add("tests", GSON.toJsonTree(event.getTests()));
        }
        if (event.getTest() != null) copyTest(event.getTest(), result);
        if (event.getOutput() != null && !event.getOutput().isEmpty()) {
            result.add("output", GSON.toJsonTree(event.getOutput()));
        }
        return result;
    }

    private static void copyTest(FlixTestRunEvent.TestRef test, JsonObject result) {
        result.addProperty("name", test.getName());
        result.addProperty("skip", test.isSkip());
        if (test.getFile() != null) {
            result.addProperty("file", test.getFile());
            result.addProperty("startLine", test.getStartLine());
            result.addProperty("startCol", test.getStartCol());
            result.addProperty("endLine", test.getEndLine());
            result.addProperty("endCol", test.getEndCol());
        }
    }

    private void start(LspTestProcessHandler handler) {
        runs.put(handler.runId, handler);
        LanguageServerManager.getInstance(project)
                .getLanguageServer(FlixLanguageServer.SERVER_ID)
                .orTimeout(SERVER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((item, failure) -> {
                    if (failure != null || item == null || !(item.getServer() instanceof FlixLanguageServerApi api)) {
                        fallback(handler);
                        return;
                    }
                    // Stop may have removed the run while the server was still starting.
                    if (runs.get(handler.runId) != handler) return;
                    FlixTestRunRequest request = new FlixTestRunRequest();
                    request.setRunId(handler.runId);
                    request.setFilters(handler.filters);
                    api.testRun(request).whenComplete((response, requestFailure) -> {
                        if (requestFailure != null || response == null ||
                                response.getProtocolVersion() != FlixTestRunRequest.PROTOCOL_VERSION ||
                                !"accepted".equals(response.getStatus())) {
                            // A lost response can still follow an accepted request. Cancellation is
                            // idempotent and prevents a hidden LSP run from overlapping the CLI fallback.
                            FlixTestCancelRequest cancel = new FlixTestCancelRequest();
                            cancel.setRunId(handler.runId);
                            api.testCancel(cancel);
                            fallback(handler);
                        }
                    });
                });
    }

    private void fallback(LspTestProcessHandler handler) {
        if (!runs.remove(handler.runId, handler)) return;
        handler.startFallback();
    }

    private void cancel(LspTestProcessHandler handler) {
        if (!runs.remove(handler.runId, handler)) return;
        LanguageServerManager.getInstance(project).getLanguageServer(FlixLanguageServer.SERVER_ID)
                .thenAccept(item -> {
                    if (item != null && item.getServer() instanceof FlixLanguageServerApi api) {
                        FlixTestCancelRequest request = new FlixTestCancelRequest();
                        request.setRunId(handler.runId);
                        api.testCancel(request);
                    }
                });
    }

    private void finished(LspTestProcessHandler handler) {
        runs.remove(handler.runId, handler);
    }

    private static final class LspTestProcessHandler extends ProcessHandler {
        private final FlixLspTestRunnerImpl owner;
        private final String runId = UUID.randomUUID().toString();
        private final List<String> filters;
        private final List<String> fallbackCommand;
        private final Path workingDirectory;
        private volatile ProcessHandler fallback;
        private volatile boolean failed;

        private LspTestProcessHandler(
                FlixLspTestRunnerImpl owner,
                List<String> filters,
                List<String> fallbackCommand,
                Path workingDirectory) {
            this.owner = owner;
            this.filters = List.copyOf(filters);
            this.fallbackCommand = List.copyOf(fallbackCommand);
            this.workingDirectory = workingDirectory;
        }

        @Override
        public void startNotify() {
            super.startNotify();
            owner.start(this);
        }

        private void accept(FlixTestRunEvent event) {
            if (isProcessTerminated()) return;
            if (event.getProtocolVersion() != FlixTestRunRequest.PROTOCOL_VERSION) {
                JsonObject mismatch = new JsonObject();
                mismatch.addProperty("event", "start");
                mismatch.addProperty("protocolVersion", event.getProtocolVersion());
                mismatch.add("tests", GSON.toJsonTree(List.of()));
                emit(mismatch);
                finish(1);
                return;
            }
            if ("diagnostics".equals(event.getEvent())) {
                for (String diagnostic : event.getDiagnostics()) {
                    notifyTextAvailable(diagnostic + "\n", ProcessOutputTypes.STDERR);
                }
                finish(1);
                return;
            }
            if ("failed".equals(event.getEvent())) failed = true;
            emit(FlixLspTestRunnerImpl.jsonLine(event));
            if ("finished".equals(event.getEvent())) {
                finish(event.isCancelled() ? 130 : failed ? 1 : 0);
            }
        }

        private void emit(JsonObject event) {
            notifyTextAvailable(GSON.toJson(event) + "\n", ProcessOutputTypes.STDOUT);
        }

        private void startFallback() {
            if (isProcessTerminated()) return;
            try {
                KillableColoredProcessHandler child = new KillableColoredProcessHandler(
                        new GeneralCommandLine(fallbackCommand).withWorkDirectory(workingDirectory.toFile()));
                fallback = child;
                child.addProcessListener(new ProcessAdapter() {
                    @Override
                    public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                        notifyTextAvailable(event.getText(), outputType);
                    }

                    @Override
                    public void processTerminated(@NotNull ProcessEvent event) {
                        finish(event.getExitCode());
                    }
                });
                child.startNotify();
            } catch (ExecutionException failure) {
                notifyTextAvailable("Unable to start Flix tests: " + failure.getMessage() + "\n", ProcessOutputTypes.STDERR);
                finish(1);
            }
        }

        private void finish(int exitCode) {
            if (isProcessTerminated()) return;
            owner.finished(this);
            notifyProcessTerminated(exitCode);
        }

        @Override
        protected void destroyProcessImpl() {
            ProcessHandler child = fallback;
            if (child != null && !child.isProcessTerminated()) child.destroyProcess();
            else owner.cancel(this);
            if (!isProcessTerminated()) notifyProcessTerminated(130);
        }

        @Override
        protected void detachProcessImpl() {
            destroyProcessImpl();
        }

        @Override
        public boolean detachIsDefault() {
            return false;
        }

        @Override
        public @Nullable OutputStream getProcessInput() {
            ProcessHandler child = fallback;
            return child == null ? null : child.getProcessInput();
        }
    }
}
