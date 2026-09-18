package de.wstein.flixplugin;

import com.google.gson.JsonObject;
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FlixLspTestRunnerTest {
    @Test
    public void customRequestsAndNotificationUseTheCompilerMethodNames() {
        assertTrue(ServiceEndpoints.getSupportedMethods(FlixLanguageServerApi.class)
                .containsKey("flix/test/run"));
        assertTrue(ServiceEndpoints.getSupportedMethods(FlixLanguageServerApi.class)
                .containsKey("flix/test/cancel"));
        assertTrue(ServiceEndpoints.getSupportedMethods(FlixLanguageClientApi.class)
                .containsKey("flix/test/event"));
    }

    @Test
    public void anLspFailureBecomesTheExistingJsonlEventShape() {
        FlixTestRunEvent.TestRef test = new FlixTestRunEvent.TestRef();
        test.setName("Suite.fails");
        test.setFile("src/Suite.flix");
        test.setStartLine(7);
        test.setStartCol(3);
        test.setEndLine(7);
        test.setEndCol(24);

        FlixTestRunEvent event = new FlixTestRunEvent();
        event.setProtocolVersion(FlixTestRunRequest.PROTOCOL_VERSION);
        event.setEvent("failed");
        event.setTest(test);
        event.setNanos(42);
        event.setOutput(List.of("Assertion Error"));

        JsonObject json = FlixLspTestRunnerImpl.jsonLine(event);

        assertEquals("failed", json.get("event").getAsString());
        assertEquals("Suite.fails", json.get("name").getAsString());
        assertEquals("src/Suite.flix", json.get("file").getAsString());
        assertEquals(7, json.get("startLine").getAsInt());
        assertEquals(42, json.get("nanos").getAsLong());
        assertEquals("Assertion Error", json.getAsJsonArray("output").get(0).getAsString());
    }

    @Test
    public void unavailableTimedOutAndOlderServersRequireCliFallback() {
        assertTrue(FlixLspTestRunnerImpl.serverRequiresFallback(null, false, false));
        assertTrue(FlixLspTestRunnerImpl.serverRequiresFallback(new TimeoutException(), false, false));
        assertTrue(FlixLspTestRunnerImpl.serverRequiresFallback(null, true, false));
        assertFalse(FlixLspTestRunnerImpl.serverRequiresFallback(null, true, true));
    }

    @Test
    public void onlyAnAcceptedCurrentProtocolResponseKeepsTheLspRun() {
        FlixTestRunResponse accepted = response(FlixTestRunRequest.PROTOCOL_VERSION, "accepted");
        assertFalse(FlixLspTestRunnerImpl.requestRequiresFallback(null, accepted));

        assertTrue(FlixLspTestRunnerImpl.requestRequiresFallback(new IllegalStateException("method not found"), null));
        assertTrue(FlixLspTestRunnerImpl.requestRequiresFallback(null, null));
        assertTrue(FlixLspTestRunnerImpl.requestRequiresFallback(
                null, response(FlixTestRunRequest.PROTOCOL_VERSION + 1, "accepted")));
        assertTrue(FlixLspTestRunnerImpl.requestRequiresFallback(
                null, response(FlixTestRunRequest.PROTOCOL_VERSION, "rejected")));
    }

    @Test
    public void concurrentFallbackCausesCanClaimTheCliOnlyOnce() {
        ConcurrentHashMap<String, Object> active = new ConcurrentHashMap<>();
        Object run = new Object();
        active.put("run-1", run);

        assertTrue(FlixLspTestRunnerImpl.claimFallback(active, "run-1", run));
        assertFalse(FlixLspTestRunnerImpl.claimFallback(active, "run-1", run));
    }

    private static FlixTestRunResponse response(int protocolVersion, String status) {
        FlixTestRunResponse response = new FlixTestRunResponse();
        response.setProtocolVersion(protocolVersion);
        response.setStatus(status);
        return response;
    }
}
