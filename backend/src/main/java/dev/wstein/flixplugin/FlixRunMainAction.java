package dev.wstein.flixplugin;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.RunContentExecutor;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.commands.LSPCommand;
import com.redhat.devtools.lsp4ij.commands.LSPCommandAction;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * Handles the "flix.runMain" LSP command the Flix language server attaches to the "Run" CodeLens
 * above {@code def main()} -- LSP4IJ has no generic handler for arbitrary server-defined commands
 * (they map 1:1 to IntelliJ actions the way VS Code maps them to client-side JS), so without this
 * registration clicking that CodeLens fails with "Missing 'flix.runMain' command... needs to be
 * contributed by an IntelliJ plugin".
 *
 * The command's own arguments are deliberately not inspected: their shape is defined by the Flix
 * compiler team's language server implementation and wasn't verified here, whereas `flix run` (no
 * arguments) is documented and confirmed (via `scripts/flix-fork --help`) to always run the
 * project's default `main` entrypoint -- exactly what clicking "Run" above `def main()` means for
 * the common case of a project with a single entrypoint.
 */
public class FlixRunMainAction extends LSPCommandAction {

    @Override
    protected void commandPerformed(@NotNull LSPCommand command, @NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        Path jar = FlixFork.resolveJar(project);
        GeneralCommandLine commandLine = new GeneralCommandLine("java", "-jar", jar.toString(), "run");
        String basePath = project.getBasePath();
        if (basePath != null) {
            commandLine.setWorkDirectory(basePath);
        }
        try {
            OSProcessHandler processHandler = new OSProcessHandler(commandLine);
            new RunContentExecutor(project, processHandler)
                    .withTitle("flix run")
                    .withActivateToolWindow(true)
                    .run();
        } catch (ExecutionException ex) {
            throw new RuntimeException(ex);
        }
    }
}
