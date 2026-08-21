package de.wstein.flixplugin;

import com.intellij.testFramework.HeavyPlatformTestCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Whether a project can have a language server, and what is said when it
 * cannot.
 *
 * <p>
 * The answer used to be given by starting the server and letting it fail:
 * LSP4IJ wraps a failed
 * provider in {@code CannotStartServerException}, and the request that triggers
 * a start is a
 * highlighting pass -- so the reason reached the user as a stack trace out of
 * the syntax
 * highlighter, once per pass, for as long as the compiler was missing. Asking
 * first turns that into
 * a project with no server and one balloon.
 *
 * <p>
 * A real project on disk ({@link HeavyPlatformTestCase}) for the reason
 * {@code FlixForkTest}
 * gives: resolution is plain filesystem work against the project's base path.
 */
public class FlixClientFeaturesTest extends HeavyPlatformTestCase {

    public void testNothingStandsInTheWayWhenACompilerCanBeResolved() throws IOException {
        Path base = Path.of(getProject().getBasePath());
        Files.createDirectories(base);
        Files.createFile(base.resolve("flix.jar"));

        assertNull(FlixClientFeatures.problemStarting(getProject()));
    }

    public void testTheProblemSaysWhatToDoAboutIt() {
        // The state a new project is in. A refusal that does not name the file to
        // supply is a
        // feature that appears not to work.
        String problem = FlixClientFeatures.problemStarting(getProject());

        assertNotNull("a project with no compiler should report a problem", problem);
        assertTrue("the message must name the file to supply: " + problem, problem.contains("flix.jar"));
        assertTrue("the message must name the override: " + problem, problem.contains(FlixJar.JAR_ENV));
    }

    public void testTheAnswerFollowsTheCompilerRatherThanBeingCached() throws IOException {
        // A cached refusal would outlast its cause: a rebuild removes the jar for a few
        // seconds,
        // and the project stays silently unanalysed after it comes back.
        assertNotNull(FlixClientFeatures.problemStarting(getProject()));

        Path base = Path.of(getProject().getBasePath());
        Files.createDirectories(base);
        Files.createFile(base.resolve("flix.jar"));

        assertNull("the compiler appeared, and the answer did not follow it",
                FlixClientFeatures.problemStarting(getProject()));
    }

    public void testTheSameProblemIsReportedOnce() {
        // One balloon per problem, not one per file: `isEnabled` is asked for every
        // file the editor
        // opens, and a missing compiler is one fact about the project.
        FlixClientFeatures features = new FlixClientFeatures();

        assertTrue(features.isNewProblem("no compiler"));
        assertFalse(features.isNewProblem("no compiler"));
        assertTrue("a different problem is a different message", features.isNewProblem("a stale .envrc"));
        assertTrue("and the first one is new again after that", features.isNewProblem("no compiler"));
    }
}
