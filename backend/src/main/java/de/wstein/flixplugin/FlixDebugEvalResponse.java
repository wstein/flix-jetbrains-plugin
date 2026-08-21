package de.wstein.flixplugin;

import java.util.List;

/**
 * The wire form of a {@code flix/debugEval/compile} reply.
 *
 * <p>{@code status} is {@code ok}, {@code failed} or {@code rejected}, and the three are not
 * interchangeable: a rejection is the server declining — no debug build, a frame it cannot place, an
 * effect the policy forbids — while a failure is the compiler's verdict on the expression itself.
 *
 * <p>Every field may be absent, because which ones are set depends on the status. Nothing here
 * validates that; {@code FlixDebugEvalClient} is where the wire form becomes an answer with only the
 * fields its case allows.
 */
public class FlixDebugEvalResponse {

    private String status;
    private String tpe;
    private String eff;
    private List<String> diagnostics;
    private String reason;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTpe() {
        return tpe;
    }

    public void setTpe(String tpe) {
        this.tpe = tpe;
    }

    public String getEff() {
        return eff;
    }

    public void setEff(String eff) {
        this.eff = eff;
    }

    public List<String> getDiagnostics() {
        return diagnostics;
    }

    public void setDiagnostics(List<String> diagnostics) {
        this.diagnostics = diagnostics;
    }

    private String artifact;
    private String entryClass;
    private String entryMethod;
    private String valueField;
    private List<String> parameters;

    public String getArtifact() {
        return artifact;
    }

    public void setArtifact(String artifact) {
        this.artifact = artifact;
    }

    public String getEntryClass() {
        return entryClass;
    }

    public void setEntryClass(String entryClass) {
        this.entryClass = entryClass;
    }

    public String getEntryMethod() {
        return entryMethod;
    }

    public void setEntryMethod(String entryMethod) {
        this.entryMethod = entryMethod;
    }

    public String getValueField() {
        return valueField;
    }

    public void setValueField(String valueField) {
        this.valueField = valueField;
    }

    public List<String> getParameters() {
        return parameters;
    }

    public void setParameters(List<String> parameters) {
        this.parameters = parameters;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
