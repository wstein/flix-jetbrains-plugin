package de.wstein.flixplugin;

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.services.LanguageClient;

/** Standard language-client callbacks plus Flix's streamed test events. */
public interface FlixLanguageClientApi extends LanguageClient {
    @JsonNotification("flix/test/event")
    void testEvent(FlixTestRunEvent event);
}
