package dev.wstein.flixplugin;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider;

import java.nio.file.Path;

/**
 * Launches `java -jar <flix-vendor jar> lsp` -- Flix's "Plain-LSP" server (confirmed by running
 * `./scripts/flix-fork lsp`: it prints "Starting Default LSP Server..." and speaks LSP over its
 * own stdio, exactly what OSProcessStreamConnectionProvider expects). This is the same server
 * binary+command the official VS Code Flix extension downloads and runs; only the launcher
 * differs, not the server.
 */
final class FlixLanguageServer extends OSProcessStreamConnectionProvider {

    FlixLanguageServer(Project project) {
        Path jar = FlixFork.resolveJar(project);
        GeneralCommandLine commandLine = new GeneralCommandLine("java", "-jar", jar.toString(), "lsp");
        String basePath = project.getBasePath();
        if (basePath != null) {
            commandLine.setWorkDirectory(basePath);
        }
        super.setCommandLine(commandLine);
    }
}
