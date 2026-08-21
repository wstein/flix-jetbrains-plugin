package de.wstein.flixplugin;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.LanguageServerItem;
import com.redhat.devtools.lsp4ij.LanguageServerManager;
import org.flixlang.intellij.eval.FlixDebugEval;
import org.flixlang.intellij.eval.FlixDebugEvalAnswer;
import org.flixlang.intellij.eval.FlixDebugEvalArtifact;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Carries a debug-evaluation question to the language server and the answer back.
 *
 * <h2>Where this sits</h2>
 *
 * <p>The question comes from the debugger and the answer from the compiler, and the two modules
 * cannot see each other: {@code debugger} needs the Java plugin, {@code backend} needs LSP4IJ, and
 * neither may depend on the other. {@link FlixDebugEval} is the shape of the question, declared in
 * {@code language} where both can see it; this is the half that speaks LSP, registered as the
 * service implementing it. In an IDE without LSP4IJ this module is not loaded and the debugger's
 * lookup finds nothing, which is the correct outcome rather than a failure.
 *
 * <h2>What it adds to the request</h2>
 *
 * <p>Almost nothing, deliberately. Every judgement — whether the frame is one the build recorded,
 * whether the expression types, whether its effect satisfies the policy — belongs to the compiler
 * and is made there. What happens here is what cannot happen there: finding a running server,
 * waiting for it, and turning "there was no server" or "it did not answer" into an answer rather
 * than an exception.
 *
 * <h2>Blocking, and why that is right here</h2>
 *
 * <p>The caller is an evaluator with a suspended debuggee and a user waiting on a watch, so it has
 * to have the answer before it can return one. It runs on a debugger thread, never the UI thread.
 * The timeouts exist because the alternative to a slow answer is a frozen Variables view: a server
 * that is compiling a large project can take seconds, and one that has wedged takes forever.
 *
 * <p>There are two of them, and the difference matters most where the question is asked most often.
 * A breakpoint condition is evaluated on every hit of its breakpoint, so in a project with no
 * language server the wait for one is paid over and over — which is why finding a server is given
 * seconds and waiting for its answer is given half a minute.
 */
final class FlixDebugEvalClient implements FlixDebugEval {

    private static final Logger LOG = Logger.getInstance(FlixDebugEvalClient.class);

    /**
     * How long to wait for the compiler to answer.
     *
     * <p>Generous rather than snappy: the server may be part-way through a compilation when the
     * question arrives, and typing an expression means compiling the project it belongs to.
     */
    static final long ANSWER_TIMEOUT_SECONDS = 30;

    /**
     * How long to wait for there to <em>be</em> a server.
     *
     * <p>Short, and deliberately not the same number. "Is one running" is answered immediately when
     * one is, so a long wait here buys nothing and costs everything in the case that matters: a
     * breakpoint condition is evaluated on <em>every hit</em> of that breakpoint, and in a project
     * with no language server each hit would otherwise stall for the full answer timeout. A debugger
     * that pauses for half a minute per breakpoint hit reads as one that has hung.
     */
    static final long SERVER_TIMEOUT_SECONDS = 2;

    private final Project project;

    FlixDebugEvalClient(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @NotNull FlixDebugEvalAnswer compile(
            @NotNull String expression,
            @NotNull String className,
            @NotNull String methodName,
            @NotNull Policy policy,
            boolean withArtifact) {

        FlixDebugEvalRequest request = new FlixDebugEvalRequest();
        request.setExpression(expression);
        request.setClassName(className);
        request.setMethodName(methodName);
        request.setPolicy(policy.getWireName());
        request.setWithArtifact(withArtifact);

        try {
            LanguageServerItem server = LanguageServerManager.getInstance(project)
                    .getLanguageServer(FlixLanguageServer.SERVER_ID)
                    .get(SERVER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (server == null) {
                return new FlixDebugEvalAnswer.Unavailable(
                        "the Flix language server is not running, so nothing can type this expression");
            }
            if (!(server.getServer() instanceof FlixLanguageServerApi api)) {
                // The factory names a wider interface than the standard one; if that ever stops
                // being true the request has nowhere to go, and saying so beats a ClassCastException
                // arriving from inside a watch.
                return new FlixDebugEvalAnswer.Unavailable(
                        "this language server does not offer flix/debugEval/compile");
            }
            return answerOf(api.debugEvalCompile(request).get(ANSWER_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new FlixDebugEvalAnswer.Unavailable("the request was interrupted");
        } catch (Exception failed) {
            // Including a timeout, a server that died mid-request, and MethodNotFound from a server
            // older than the request. None of them says anything about the expression, so none of
            // them may be reported as though it did.
            LOG.debug("flix/debugEval/compile failed", failed);
            return new FlixDebugEvalAnswer.Unavailable(
                    "the Flix language server did not answer: " + failed.getMessage());
        }
    }

    /**
     * The wire form as an answer, with only the fields its case allows.
     *
     * <p>A status this client does not know is {@code Unavailable} rather than an assumption: a
     * newer server may answer in a way this build cannot act on, and guessing which of the three
     * cases it meant would put words in the compiler's mouth.
     */
    private static @NotNull FlixDebugEvalAnswer answerOf(FlixDebugEvalResponse response) {
        if (response == null || response.getStatus() == null) {
            return new FlixDebugEvalAnswer.Unavailable("the language server sent no answer");
        }
        return switch (response.getStatus()) {
            case "ok" -> new FlixDebugEvalAnswer.Typed(
                    orEmpty(response.getTpe()),
                    orEmpty(response.getEff()),
                    artifactOf(response));
            case "failed" -> new FlixDebugEvalAnswer.Invalid(
                    response.getDiagnostics() == null ? List.of() : List.copyOf(response.getDiagnostics()));
            case "rejected" -> new FlixDebugEvalAnswer.Unavailable(orEmpty(response.getReason()));
            default -> new FlixDebugEvalAnswer.Unavailable(
                    "the language server answered '" + response.getStatus() + "', which this plugin does not understand");
        };
    }

    /**
     * The artifact, or {@code null} when only typing was asked for.
     *
     * All-or-nothing: an artifact missing its entry class is not half an artifact, it is a reply
     * this build cannot act on, and pretending otherwise would surface as a failure inside the
     * debuggee rather than here.
     */
    private static @org.jetbrains.annotations.Nullable FlixDebugEvalArtifact artifactOf(FlixDebugEvalResponse response) {
        if (response.getArtifact() == null || response.getEntryClass() == null
                || response.getEntryMethod() == null || response.getValueField() == null) {
            return null;
        }
        return new FlixDebugEvalArtifact(
                response.getArtifact(),
                response.getEntryClass(),
                response.getEntryMethod(),
                response.getValueField(),
                response.getParameters() == null ? List.of() : List.copyOf(response.getParameters()));
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
