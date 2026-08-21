package de.wstein.flixplugin;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Whether the language server can be started at all, and what to say when it
 * cannot.
 *
 * <h2>The failure this replaces</h2>
 *
 * <p>
 * Without a compiler there is nothing to start, and {@link FlixLanguageServer}
 * said so by
 * throwing from its constructor — with a message naming the file to fix and
 * what is wrong with it.
 * The message was right; where it arrived was not. LSP4IJ wraps a failed
 * provider in
 * {@code CannotStartServerException}, and the request that triggered the start
 * is a highlighting
 * pass, so the reason reached the user as a stack trace from the syntax
 * highlighter — once per pass,
 * for as long as the compiler is missing.
 *
 * <p>
 * Answering the question *before* the start is the difference between a
 * diagnosis and an
 * accident report. A file whose compiler cannot be resolved simply has no
 * server, and the reason is
 * shown once, in a balloon, where a message about a project's configuration
 * belongs.
 *
 * <h2>Why it is asked every time rather than cached</h2>
 *
 * <p>
 * The answer changes without anything telling us: a jar is built, an
 * {@code .envrc} is edited, a
 * rebuild removes the jar for a few seconds and puts it back. Caching the
 * failure would outlast the
 * cause and leave a project silently unanalysed after the thing that broke it
 * was fixed. What the
 * check costs is a small file read and a stat, and it runs off the UI thread.
 *
 * <p>
 * The <em>notification</em> is what is remembered, not the answer: the same
 * message is shown once
 * and again only if it changes, so a missing compiler does not produce a
 * balloon per file.
 */
final class FlixClientFeatures extends LSPClientFeatures {

    /** The last problem reported, so an unchanged one is not reported again. */
    private final AtomicReference<String> reported = new AtomicReference<>();

    @Override
    public boolean isEnabled(@NotNull VirtualFile file) {
        Project project = getProject();
        String problem = problemStarting(project);
        if (problem == null) {
            // Cleared, so that a compiler that goes missing again is reported again.
            reported.set(null);
            return true;
        }
        report(project, problem);
        return false;
    }

    /**
     * What stands in the way of starting a server for {@code project}, or
     * {@code null} if nothing
     * does.
     *
     * <p>
     * Only the resolution is attempted here. Everything after it — the process, the
     * handshake —
     * belongs to a start that has actually begun, and a failure there is a failure
     * of the server
     * rather than a reason not to have one.
     */
    static @Nullable String problemStarting(@NotNull Project project) {
        try {
            FlixFork.resolveJar(project);
            return null;
        } catch (IllegalStateException noCompiler) {
            return noCompiler.getMessage();
        }
    }

    /**
     * Whether {@code problem} is one the user has not already been shown.
     *
     * <p>
     * Package-private and separate from the notification for the sake of the test:
     * the decision
     * is the part with a rule in it, and the balloon needs a project to be shown
     * in.
     */
    boolean isNewProblem(@NotNull String problem) {
        return !problem.equals(reported.getAndSet(problem));
    }

    private void report(@NotNull Project project, @NotNull String problem) {
        if (!isNewProblem(problem)) {
            return;
        }
        NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification("Flix language server not started", problem, NotificationType.WARNING)
                .notify(project);
    }

    /**
     * Registered in the backend module's descriptor; an unregistered group is
     * dropped with a log line.
     */
    static final String NOTIFICATION_GROUP = "Flix";
}
