package de.wstein.flixplugin;

import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl;
import org.jetbrains.annotations.NotNull;

/** Receives custom notifications and hands them to the matching synthetic test process. */
final class FlixLanguageClient extends LanguageClientImpl implements FlixLanguageClientApi {
    FlixLanguageClient(@NotNull Project project) {
        super(project);
    }

    @Override
    public void testEvent(FlixTestRunEvent event) {
        FlixLspTestRunnerImpl service = getProject().getService(FlixLspTestRunnerImpl.class);
        if (service != null) service.accept(event);
    }
}
