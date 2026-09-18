package de.wstein.flixplugin;

public final class FlixTestRunResponse {
    private int protocolVersion;
    private String status;
    private String runId;
    private String reason;

    public int getProtocolVersion() { return protocolVersion; }
    public void setProtocolVersion(int value) { protocolVersion = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getRunId() { return runId; }
    public void setRunId(String value) { runId = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
}
