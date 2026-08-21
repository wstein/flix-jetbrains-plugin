package de.wstein.flixplugin;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * How long the client waits, and why it is not one number.
 *
 * <p>A breakpoint condition is evaluated on <em>every hit</em> of its breakpoint. In a project with
 * no language server, each hit pays the wait for one — so "is a server running", which is answered
 * immediately when the answer is yes, must not be given the same patience as "compile this
 * expression", which legitimately takes seconds.
 *
 * <p>Collapsing the two is a one-character change that nothing else would notice: a watch would
 * still work, and only a session with no server and a conditional breakpoint would show it, as a
 * debugger that appears to hang.
 */
public class FlixDebugEvalClientTest {

    @Test
    public void findingAServerIsGivenLessPatienceThanCompiling() {
        assertTrue(
                "waiting for a server must be much shorter than waiting for an answer: "
                        + FlixDebugEvalClient.SERVER_TIMEOUT_SECONDS + "s against "
                        + FlixDebugEvalClient.ANSWER_TIMEOUT_SECONDS + "s",
                FlixDebugEvalClient.SERVER_TIMEOUT_SECONDS * 5 <= FlixDebugEvalClient.ANSWER_TIMEOUT_SECONDS);
    }

    @Test
    public void bothWaitsAreBounded() {
        // An unbounded wait on a debugger thread is a frozen Variables view with no way out.
        assertTrue(FlixDebugEvalClient.SERVER_TIMEOUT_SECONDS > 0);
        assertTrue(FlixDebugEvalClient.ANSWER_TIMEOUT_SECONDS > 0);
    }
}
