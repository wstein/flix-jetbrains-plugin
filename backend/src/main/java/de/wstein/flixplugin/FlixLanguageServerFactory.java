package de.wstein.flixplugin;

import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.LanguageServerFactory;
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures;
import com.redhat.devtools.lsp4ij.client.features.LSPWorkspaceFolderFeature;
import com.redhat.devtools.lsp4ij.features.workspaceFolder.WorkspaceFolderStrategy;
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider;
import org.eclipse.lsp4j.services.LanguageServer;
import org.jetbrains.annotations.NotNull;

public class FlixLanguageServerFactory implements LanguageServerFactory {

    @Override
    public @NotNull StreamConnectionProvider createConnectionProvider(@NotNull Project project) {
        return new FlixLanguageServer(project);
    }
    /**
     * The interface LSP4IJ builds its proxy from.
     *
     * <p>Wider than the standard one because {@code flix/debugEval/compile} is not in the standard:
     * a request outside it is reached by naming an interface that declares it, rather than by going
     * around the client. Everything else still goes through LSP4IJ unchanged.
     */
    @Override
    public @NotNull Class<? extends LanguageServer> getServerInterface() {
        return FlixLanguageServerApi.class;
    }

    /**
     * Replaces the workspace-folder feature and the enablement check; everything
     * else is LSP4IJ's
     * default. {@link FlixWorkspaceFolderStrategy} says why the folder's
     * <em>name</em> decides
     * whether the server sees the project at all, and {@link FlixClientFeatures}
     * why a missing
     * compiler is answered before a start rather than during one, and
     * {@link FlixSemanticTokensFeature} why an effect name needs a colour of its own.
     */
    @Override
    public @NotNull LSPClientFeatures createClientFeatures() {
        return new FlixClientFeatures()
                .setWorkspaceFolderFeature(new LSPWorkspaceFolderFeature() {
                    @Override
                    protected @NotNull WorkspaceFolderStrategy createStrategy() {
                        return new FlixWorkspaceFolderStrategy();
                    }
                })
                // Flix names one token type the standard does not have, `effect`, and LSP4IJ paints
                // nothing for a type it does not recognise. See FlixSemanticTokensFeature.
                .setSemanticTokensFeature(new FlixSemanticTokensFeature());
    }
}
