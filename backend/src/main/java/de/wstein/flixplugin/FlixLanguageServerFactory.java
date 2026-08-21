package de.wstein.flixplugin;

import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.LanguageServerFactory;
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures;
import com.redhat.devtools.lsp4ij.client.features.LSPWorkspaceFolderFeature;
import com.redhat.devtools.lsp4ij.features.workspaceFolder.WorkspaceFolderStrategy;
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider;
import org.jetbrains.annotations.NotNull;

public class FlixLanguageServerFactory implements LanguageServerFactory {

    @Override
    public @NotNull StreamConnectionProvider createConnectionProvider(@NotNull Project project) {
        return new FlixLanguageServer(project);
    }

    /**
     * Replaces the workspace-folder feature and the enablement check; everything
     * else is LSP4IJ's
     * default. {@link FlixWorkspaceFolderStrategy} says why the folder's
     * <em>name</em> decides
     * whether the server sees the project at all, and {@link FlixClientFeatures}
     * why a missing
     * compiler is answered before a start rather than during one.
     */
    @Override
    public @NotNull LSPClientFeatures createClientFeatures() {
        return new FlixClientFeatures().setWorkspaceFolderFeature(new LSPWorkspaceFolderFeature() {
            @Override
            protected @NotNull WorkspaceFolderStrategy createStrategy() {
                return new FlixWorkspaceFolderStrategy();
            }
        });
    }
}
