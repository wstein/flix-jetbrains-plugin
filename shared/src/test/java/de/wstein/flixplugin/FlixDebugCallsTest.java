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
                {"formatVersion":1,"calls":[
                  {"source":"/work/src/Main.flix","startLine":7,"startCol":9,"endLine":7,"endCol":15,"label":"Tuning.path","className":"dev.flix.gen.Tuning$Def$path","methodName":"staticApply"}
                ]}
                """);

        var calls = FlixDebugCalls.read(project).callsOn("Main.flix", 7);
        assertEquals(1, calls.size());
        assertEquals("Tuning.path", calls.getFirst().label());
        assertEquals("dev.flix.gen.Tuning$Def$path", calls.getFirst().className());
        assertTrue(FlixDebugCalls.read(project).callsOn("Main.flix", 8).isEmpty());
    }

    @Test
    public void absentMalformedAndFutureSidecarsFailClosed() throws Exception {
        Path project = Files.createTempDirectory("flix-debug-calls-test");
        assertTrue(FlixDebugCalls.read(project).isEmpty());
        Path sidecar = project.resolve(FlixDebugCalls.CALLS_PATH);
        Files.createDirectories(sidecar.getParent());
        Files.writeString(sidecar, "{\"formatVersion\":2,\"calls\":[]}");
        assertTrue(FlixDebugCalls.read(project).isEmpty());
        Files.writeString(sidecar, "{\"formatVersion\":1,\"calls\":[{broken}]}");
        assertTrue(FlixDebugCalls.read(project).isEmpty());
    }

    @Test
    public void aBareNameDoesNotMergeDuplicateSources() throws Exception {
        Path project = Files.createTempDirectory("flix-debug-calls-test");
        Path sidecar = project.resolve(FlixDebugCalls.CALLS_PATH);
        Files.createDirectories(sidecar.getParent());
        Files.writeString(sidecar, """
                {"formatVersion":1,"calls":[
                  {"source":"/one/Main.flix","startLine":1,"startCol":1,"endLine":1,"endCol":4,"label":"one","className":"Def$one","methodName":"staticApply"},
                  {"source":"/two/Main.flix","startLine":1,"startCol":1,"endLine":1,"endCol":4,"label":"two","className":"Def$two","methodName":"staticApply"}
                ]}
                """);

        assertTrue(FlixDebugCalls.read(project).callsOn("Main.flix", 1).isEmpty());
        assertEquals("one", FlixDebugCalls.read(project).callsOn("/one/Main.flix", 1).getFirst().label());
    }
}
