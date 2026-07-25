package dev.wstein.flixplugin;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.RunConfigurationOptions;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.redhat.devtools.lsp4ij.dap.DebugMode;
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationOptions;
import com.redhat.devtools.lsp4ij.dap.configurations.options.AttachConfigurable;
import com.redhat.devtools.lsp4ij.dap.definitions.DebugAdapterServerDefinition;
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptor;
import com.redhat.devtools.lsp4ij.dap.descriptors.ServerReadyConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridges LSP4IJ's DAP client to the project's existing FlixDebugAdapter.java, embedded verbatim
 * as a plugin resource by the Gradle build (see build.gradle.kts's copyDapServer task).
 *
 * {@link #getDebugMode()} and the DAP-protocol "request" field are NOT independent, despite
 * looking like two separate concerns (LSP4IJ obtaining the server process vs. what the debuggee
 * connection does) -- confirmed by reading LSP4IJ's own DAPClient source after this actually broke
 * live: `debugMode == DebugMode.LAUNCH` makes LSP4IJ call `getDebugProtocolServer().launch(params)`
 * -- an *actual DAP `launch` command* -- rather than `attach(params)`, regardless of what "request"
 * key is inside the params map. So getDapParameters() below sets "request": "launch" to match, and
 * FlixDebugAdapter.java's dispatcher treats "launch" and "attach" identically (both just mean
 * "attach to the JDWP host:port in `arguments`" -- there's no real "launch a program" concept for
 * an adapter that never launches anything itself).
 */
public class FlixDebugAdapterDescriptor extends DebugAdapterDescriptor {

    private static final String DEFAULT_HOST = "localhost";
    private static final String DEFAULT_PORT = "5005";

    /**
     * The developer guide's own "embed the DAP server" example points at a
     * DebugAdapterDescriptorFactory.getDebugAdapterServerPath(pluginId, relativePath) helper --
     * that method doesn't actually exist anywhere in LSP4IJ 0.20.1's source (confirmed with a
     * repo-wide search; it's only ever referenced in the doc's own sample, which does not compile
     * as written). Extracting the bundled resource to a temp file via the classloader is standard
     * JDK API with no LSP4IJ-specific assumptions, so it's used here instead.
     */
    private static Path resolveDapServerPath() {
        try (InputStream in = FlixDebugAdapterDescriptor.class.getResourceAsStream("/dap/FlixDebugAdapter.java")) {
            if (in == null) {
                throw new IllegalStateException("dap/FlixDebugAdapter.java missing from plugin resources");
            }
            Path tmp = Files.createTempFile("FlixDebugAdapter", ".java");
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final Path DAP_SERVER_PATH = resolveDapServerPath();

    public FlixDebugAdapterDescriptor(@NotNull RunConfigurationOptions options,
                                      @NotNull ExecutionEnvironment environment,
                                      @Nullable DebugAdapterServerDefinition serverDefinition) {
        super(options, environment, serverDefinition);
    }

    @Override
    public ProcessHandler startServer() throws ExecutionException {
        // FlixDebugAdapter.java is a single-file source-code program (JEP 330), launched the same
        // way bin/flix-debug-adapter launches it for VS Code -- just with --port instead of stdio.
        String command = "java --add-modules jdk.jdi " + DAP_SERVER_PATH + " --port ${port}";
        GeneralCommandLine commandLine = createStartServerCommandLine(command);
        return startServer(commandLine);
    }

    @Override
    public @NotNull ServerReadyConfig getServerReadyConfig(@NotNull DebugMode debugMode) {
        // Matches the exact line FlixDebugAdapter.runOverSocket() prints once its server socket is
        // bound and ready to accept the one DAP client connection LSP4IJ will make (verified with a
        // standalone socket smoke test; not yet verified against LSP4IJ's own pattern matching).
        return new ServerReadyConfig("Listening for DAP client on port ");
    }

    @Override
    public @NotNull DebugMode getDebugMode() {
        return DebugMode.LAUNCH;
    }

    @Override
    public @NotNull Map<String, Object> getDapParameters() {
        String host = DEFAULT_HOST;
        String port = DEFAULT_PORT;
        if (options instanceof AttachConfigurable attach) {
            String attachAddress = attach.getAttachAddress();
            String attachPort = attach.getAttachPort();
            if (attachAddress != null && !attachAddress.isBlank()) {
                host = attachAddress;
            }
            if (attachPort != null && !attachPort.isBlank()) {
                port = attachPort;
            }
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "flix");
        params.put("request", "launch");
        params.put("hostName", host);
        params.put("port", Integer.parseInt(port));
        return params;
    }

    @Override
    public @Nullable FileType getFileType() {
        // No IntelliJ FileType is registered for .flix by this plugin (LSP uses
        // fileNamePatternMapping precisely to avoid needing one and to keep TextMate
        // highlighting); DAP file association is done per-run-configuration on the Mappings tab.
        return null;
    }

    @Override
    public boolean isDebuggableFile(@NotNull VirtualFile file, @NotNull Project project) {
        // The base class's isDebuggableFile() only consults the plugin-level serverDefinition
        // (from plugin.xml's <debugAdapterServer>, which has no file mapping of its own -- there's
        // no plugin.xml equivalent of the LSP fileNamePatternMapping extension point for DAP). The
        // per-run-configuration Mappings tab data lives on DAPRunConfigurationOptions instead, and
        // nothing consults it unless explicitly checked here -- confirmed by comparing against
        // DefaultDebugAdapterDescriptor.isDebuggableFile(), which does exactly this same check for
        // generic user-defined DAP servers. Without this override every breakpoint is rejected as
        // "not supported" regardless of what the Mappings tab shows.
        if (options instanceof DAPRunConfigurationOptions dapOptions) {
            return dapOptions.isDebuggableFile(file, project);
        }
        return false;
    }
}
