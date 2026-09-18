package de.wstein.flixplugin;

import java.util.ArrayList;
import java.util.List;

public final class FlixTestRunRequest {
    public static final int PROTOCOL_VERSION = 1;

    private int protocolVersion = PROTOCOL_VERSION;
    private String runId;
    private List<String> filters = new ArrayList<>();

    public int getProtocolVersion() { return protocolVersion; }
    public void setProtocolVersion(int value) { protocolVersion = value; }
    public String getRunId() { return runId; }
    public void setRunId(String value) { runId = value; }
    public List<String> getFilters() { return filters; }
    public void setFilters(List<String> value) { filters = value; }
}
