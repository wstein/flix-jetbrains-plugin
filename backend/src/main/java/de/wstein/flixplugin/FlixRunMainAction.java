package de.wstein.flixplugin;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.RunContentExecutor;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.commands.LSPCommand;
import com.redhat.devtools.lsp4ij.commands.LSPCommandAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;

/**
 * Handles the "flix.runMain" LSP command the Flix language server attaches to
 * the "Run" CodeLens
 * above an entry point -- LSP4IJ has no generic handler for arbitrary
 * server-defined commands
 * (they map 1:1 to IntelliJ actions the way VS Code maps them to client-side
 * JS), so without this
 * registration clicking that CodeLens fails with "Missing 'flix.runMain'
 * command... needs to be
 * contributed by an IntelliJ plugin".
 *
 * <p>
 * The command's single argument is the entry point's symbol, and it is
 * honoured. The server
 * sends {@code Command("▶ Run", "flix.runMain", List(JString(sym.toString)))}
 * for <em>every</em>
 * entry point it finds in the file, not only {@code main} (see
 * {@code CodeLensProvider
 * .getRunCodeLenses}), so ignoring it made the CodeLens above one entry point
 * silently run a
 * different one.
 *
 * <p>
 * The round-trip is exact, and checked against the compiler rather than
 * assumed:
 * {@code Symbol.DefnSym.toString} renders
 * {@code namespace.mkString(".") + "." + name}, and
 * {@code --entrypoint} parses its value with {@code Symbol.mkDefnSym(fqn)},
 * whose {@code split}
 * takes everything before the last dot as the namespace. What the server sends
 * is therefore
 * precisely what the flag accepts.
 *
 * <p>
 * Bare {@code flix run} remains the fallback when no argument arrives, which is
 * what a server
 * predating the argument, or a hand-invoked action, produces. That runs the
 * project's default
 * entry point.
 */
public class FlixRunMainAction extends LSPCommandAction {

    @Override
    protected void commandPerformed(@NotNull LSPCommand command, @NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        Path jar = FlixFork.resolveJar(project);
        String entryPoint = entryPointOf(command.getArguments());

        String basePath = project.getBasePath();
        GeneralCommandLine commandLine = new GeneralCommandLine(
                FlixLaunchCommand.run(FlixJar.javaExecutable(basePath), jar, entryPoint));
        if (basePath != null) {
            commandLine.setWorkDirectory(basePath);
        }

        String title = entryPoint == null ? "flix run" : "flix run " + entryPoint;
        try {
            OSProcessHandler processHandler = new OSProcessHandler(commandLine);
            new RunContentExecutor(project, processHandler)
                    .withTitle(title)
                    .withActivateToolWindow(true)
                    .run();
        } catch (ExecutionException ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * The entry-point symbol carried by the command's arguments, or {@code null} to
     * run the project
     * default.
     *
     * <p>
     * Takes the argument list rather than the {@link LSPCommand} so the selection
     * can be tested
     * on its own, the empty case included; {@code LSPCommand}'s constructor is
     * package-private, so
     * a test that needed one could not exercise the part most worth checking.
     *
     * <p>
     * Indexes the list here rather than calling
     * {@code LSPCommand.getArgumentAt(0)}, which is
     * unsafe for exactly the case this method is supposed to handle. Its bounds
     * check is
     * {@code index > arguments.size()}, so index 0 against an empty list falls
     * through to
     * {@code get(0)} and throws {@link IndexOutOfBoundsException} — turning "server
     * sent no
     * arguments", which should quietly run the project default, into a failed
     * CodeLens click.
     * {@link LSPCommand#getArguments()} returns an empty list instead of null and
     * performs the same
     * JSON conversion, so it is both safe and equivalent.
     *
     * <p>
     * Defensive about the argument's shape on purpose: it arrives as JSON from a
     * server this
     * plugin does not control, and anything unusable has to degrade to "run the
     * default" rather
     * than emit {@code --entrypoint} with nothing after it, which the compiler
     * rejects with an
     * error the user cannot act on.
     */
    static @Nullable String entryPointOf(@Nullable List<?> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return null;
        }
        if (!(arguments.get(0) instanceof String symbol)) {
            return null;
        }
        String trimmed = symbol.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
