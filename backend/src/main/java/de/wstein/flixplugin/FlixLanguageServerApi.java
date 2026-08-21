package de.wstein.flixplugin;

import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageServer;

import java.util.concurrent.CompletableFuture;

/**
 * The Flix language server, plus the requests that are not in the standard.
 *
 * <p>LSP4IJ builds its proxy from whatever interface {@code LanguageServerFactory.getServerInterface}
 * names, so a request outside the standard is reached by naming a wider interface rather than by
 * going around the client. The proxy still speaks LSP for everything else.
 *
 * <p>The method name is a string here and a string in the server's own annotation, and LSP4J
 * dispatches by exact match. A disagreement is answered with {@code MethodNotFound}, which reaches a
 * user as a debugger that has stopped responding rather than as two names that do not agree — so
 * both ends are pinned by tests, one in each repository.
 */
public interface FlixLanguageServerApi extends LanguageServer {

    /**
     * Types an expression against the frame a debugger is paused in.
     *
     * <p>Runs nothing. See {@code DebugEvalProvider} in the compiler for what the answer means.
     */
    @JsonRequest("flix/debugEval/compile")
    CompletableFuture<FlixDebugEvalResponse> debugEvalCompile(FlixDebugEvalRequest params);
}
