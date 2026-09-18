package de.wstein.flixplugin;

import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl;
import org.flixlang.intellij.run.FlixLspTestRunner;
import org.jetbrains.annotations.NotNull;

/** Receives custom notifications and hands them to the matching synthetic test process. */
final class FlixLanguageClient extends LanguageClientImpl implements FlixLanguageClientApi {
    FlixLanguageClient(@NotNull Project project) {
        super(project);
    }

    @Override
    public void testEvent(FlixTestRunEvent event) {
        FlixLspTestRunner service = getProject().getService(FlixLspTestRunner.class);
        if (service instanceof FlixLspTestRunnerImpl runner) runner.accept(event);
    }
}
