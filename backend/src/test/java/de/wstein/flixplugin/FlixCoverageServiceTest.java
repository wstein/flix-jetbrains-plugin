package de.wstein.flixplugin;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class FlixCoverageServiceTest {
    @Test
    public void parsesCoveredAndUncoveredLinesByNormalizedSourcePath() {
        String report = "{\"formatVersion\":1,\"partial\":false,\"files\":[{" +
                "\"path\":\"src/../src/Main.flix\",\"lines\":[" +
                "{\"line\":4,\"covered\":true,\"hitCount\":3}," +
                "{\"line\":8,\"covered\":false,\"hitCount\":0}]}]}";
        FlixCoverageService.Snapshot snapshot = FlixCoverageService.Snapshot.parse(report, false);

        FlixCoverageService.Line covered = snapshot.line("src/Main.flix", 4);
        FlixCoverageService.Line uncovered = snapshot.line("src/Main.flix", 8);
        assertEquals(FlixCoverageService.State.COVERED, covered.getState());
        assertEquals(3, covered.getHitCount());
        assertEquals(FlixCoverageService.State.UNCOVERED, uncovered.getState());
        assertNull(snapshot.line("src/Main.flix", 9));
    }

    @Test
    public void aPartialEventNeverClaimsThatAReportedLineIsDefinitivelyCoveredOrUncovered() {
        String report = "{\"formatVersion\":1,\"partial\":false,\"files\":[{" +
                "\"path\":\"src/Main.flix\",\"lines\":[" +
                "{\"line\":4,\"covered\":true,\"hitCount\":1}," +
                "{\"line\":8,\"covered\":false,\"hitCount\":0}]}]}";
        FlixCoverageService.Snapshot snapshot = FlixCoverageService.Snapshot.parse(report, true);

        assertEquals(FlixCoverageService.State.PARTIAL, snapshot.line("src/Main.flix", 4).getState());
        assertEquals(FlixCoverageService.State.PARTIAL, snapshot.line("src/Main.flix", 8).getState());
    }
}
