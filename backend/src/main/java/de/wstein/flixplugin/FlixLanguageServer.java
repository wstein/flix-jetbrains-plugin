package de.wstein.flixplugin;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider;
import org.flixlang.intellij.settings.FlixSettings;

import java.nio.file.Path;
import java.util.List;

/**
 * Launches `java -jar <flix.jar> lsp` -- Flix's "Plain-LSP" server (confirmed
 * by running
 * `./scripts/flix-fork lsp`: it prints "Starting Default LSP Server..." and
 * speaks LSP over its
 * own stdio, exactly what OSProcessStreamConnectionProvider expects). This is
 * the same server
 * binary+command the official VS Code Flix extension downloads and runs; only
 * the launcher
 * differs, not the server.
 *
 * <p>
 * The user's extra JVM and Flix arguments ({@link FlixSettings}) are applied
 * here, in the two
 * positions {@link FlixLaunchCommand#task} defines: a JVM option before
 * {@code -jar}, a compiler
 * option after the subcommand. The same settings reach every task, so one
 * project setting describes
 * every Flix process the plugin starts -- except the debug configuration, which
 * has its own fields
 * for the reason {@code FlixSettings} states.
 */
final class FlixLanguageServer extends OSProcessStreamConnectionProvider {

    FlixLanguageServer(Project project) {
        Path jar = FlixFork.resolveJar(project);
        FlixSettings settings = FlixSettings.Companion.getInstance(project);
        String basePath = project.getBasePath();
        // The same JDK the tasks and the debug configuration use. The server analyses
        // the buffers
        // the editor shows; running it on a different runtime than `flix build` uses is
        // the
        // editor-versus-terminal disagreement this resolution exists to close.
        List<String> command = FlixLaunchCommand.task(
                FlixJar.javaExecutable(basePath),
                jar,
                "lsp",
                settings.getJvmArguments(),
                settings.getFlixArguments());
        GeneralCommandLine commandLine = new GeneralCommandLine(command);
        if (basePath != null) {
            commandLine.setWorkDirectory(basePath);
        }
        super.setCommandLine(commandLine);
    }
}
