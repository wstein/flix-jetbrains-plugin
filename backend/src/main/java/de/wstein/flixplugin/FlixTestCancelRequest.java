package de.wstein.flixplugin;

public final class FlixTestCancelRequest {
    private int protocolVersion = FlixTestRunRequest.PROTOCOL_VERSION;
    private String runId;

    public int getProtocolVersion() { return protocolVersion; }
    public void setProtocolVersion(int value) { protocolVersion = value; }
    public String getRunId() { return runId; }
    public void setRunId(String value) { runId = value; }
}
