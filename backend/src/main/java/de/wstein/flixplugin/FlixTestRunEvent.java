package de.wstein.flixplugin;

import java.util.ArrayList;
import java.util.List;

public final class FlixTestRunEvent {
    private int protocolVersion;
    private String runId;
    private String event;
    private TestRef test;
    private List<TestRef> tests = new ArrayList<>();
    private long nanos;
    private List<String> output = new ArrayList<>();
    private List<String> diagnostics = new ArrayList<>();
    private boolean cancelled;

    public int getProtocolVersion() { return protocolVersion; }
    public void setProtocolVersion(int value) { protocolVersion = value; }
    public String getRunId() { return runId; }
    public void setRunId(String value) { runId = value; }
    public String getEvent() { return event; }
    public void setEvent(String value) { event = value; }
    public TestRef getTest() { return test; }
    public void setTest(TestRef value) { test = value; }
    public List<TestRef> getTests() { return tests; }
    public void setTests(List<TestRef> value) { tests = value; }
    public long getNanos() { return nanos; }
    public void setNanos(long value) { nanos = value; }
    public List<String> getOutput() { return output; }
    public void setOutput(List<String> value) { output = value; }
    public List<String> getDiagnostics() { return diagnostics; }
    public void setDiagnostics(List<String> value) { diagnostics = value; }
    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean value) { cancelled = value; }

    public static final class TestRef {
        private String name;
        private boolean skip;
        private String file;
        private int startLine;
        private int startCol;
        private int endLine;
        private int endCol;

        public String getName() { return name; }
        public void setName(String value) { name = value; }
        public boolean isSkip() { return skip; }
        public void setSkip(boolean value) { skip = value; }
        public String getFile() { return file; }
        public void setFile(String value) { file = value; }
        public int getStartLine() { return startLine; }
        public void setStartLine(int value) { startLine = value; }
        public int getStartCol() { return startCol; }
        public void setStartCol(int value) { startCol = value; }
        public int getEndLine() { return endLine; }
        public void setEndLine(int value) { endLine = value; }
        public int getEndCol() { return endCol; }
        public void setEndCol(int value) { endCol = value; }
    }
}
