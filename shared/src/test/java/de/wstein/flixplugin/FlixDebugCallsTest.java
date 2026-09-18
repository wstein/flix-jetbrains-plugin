package de.wstein.flixplugin;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FlixDebugCallsTest {

    @Test
    public void readsCallsAndMatchesAbsoluteSourcesByBaseName() throws Exception {
        Path project = Files.createTempDirectory("flix-debug-calls-test");
        Path sidecar = project.resolve(FlixDebugCalls.CALLS_PATH);
        Files.createDirectories(sidecar.getParent());
        Files.writeString(sidecar, """
                {"formatVersion":2,"sources":{
                  "/work/src/Main.flix":[
                    {"range":[7,9,7,15],"name":"Tuning.path","target":{"className":"dev.flix.gen.Tuning$Def$path"}},
                    {"range":[9,4,10,12],"name":"Worker.run","target":{"className":"Worker.Def$run","methodName":"applyFrame"}}
                  ]
                }}
                """);

        var calls = FlixDebugCalls.read(project).callsOn("Main.flix", 7);
        assertEquals(1, calls.size());
        assertEquals("Tuning.path", calls.getFirst().label());
        assertEquals("dev.flix.gen.Tuning$Def$path", calls.getFirst().className());
        assertEquals("staticApply", calls.getFirst().methodName());
        assertTrue(FlixDebugCalls.read(project).callsOn("Main.flix", 8).isEmpty());
        assertEquals("applyFrame", FlixDebugCalls.read(project).callsOn("Main.flix", 9).getFirst().methodName());
        assertEquals("Worker.run", FlixDebugCalls.read(project).callsOn("Main.flix", 10).getFirst().label());
    }

    @Test
    public void absentMalformedAndFutureSidecarsFailClosed() throws Exception {
        Path project = Files.createTempDirectory("flix-debug-calls-test");
        assertTrue(FlixDebugCalls.read(project).isEmpty());
        Path sidecar = project.resolve(FlixDebugCalls.CALLS_PATH);
        Files.createDirectories(sidecar.getParent());
        Files.writeString(sidecar, "{\"formatVersion\":3,\"sources\":{}}");
        assertTrue(FlixDebugCalls.read(project).isEmpty());
        Files.writeString(sidecar, "{\"formatVersion\":1,\"calls\":[]}");
        assertTrue(FlixDebugCalls.read(project).isEmpty());
        Files.writeString(sidecar, "{\"formatVersion\":2,\"sources\":{\"Main.flix\":[{broken}]}}");
        assertTrue(FlixDebugCalls.read(project).isEmpty());
    }

    @Test
    public void aBareNameDoesNotMergeDuplicateSources() throws Exception {
        Path project = Files.createTempDirectory("flix-debug-calls-test");
        Path sidecar = project.resolve(FlixDebugCalls.CALLS_PATH);
        Files.createDirectories(sidecar.getParent());
        Files.writeString(sidecar, """
                {"formatVersion":2,"sources":{
                  "/one/Main.flix":[{"range":[1,1,1,4],"name":"one","target":{"className":"Def$one"}}],
                  "/two/Main.flix":[{"range":[1,1,1,4],"name":"two","target":{"className":"Def$two"}}]
                }}
                """);

        assertTrue(FlixDebugCalls.read(project).callsOn("Main.flix", 1).isEmpty());
        assertEquals("one", FlixDebugCalls.read(project).callsOn("/one/Main.flix", 1).getFirst().label());
    }
}
