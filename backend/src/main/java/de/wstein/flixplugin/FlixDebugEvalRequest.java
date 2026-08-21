package de.wstein.flixplugin;

/**
 * The wire form of a {@code flix/debugEval/compile} request.
 *
 * <p>A plain mutable bean because that is what LSP4J's reflective JSON binding needs on the way out,
 * and the field names are the protocol: they must match the server's own bean exactly.
 */
public class FlixDebugEvalRequest {

    private String className;
    private String methodName;
    private String expression;
    private String policy = "pure";

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public String getExpression() {
        return expression;
    }

    public void setExpression(String expression) {
        this.expression = expression;
    }

    /**
     * Whether to ask for the classes that would run the expression, not only its type.
     *
     * Off unless asked: typing is one compilation and an artifact is a second one that also emits,
     * and a watch asks the first on every step.
     */
    private boolean withArtifact;

    public boolean isWithArtifact() {
        return withArtifact;
    }

    public void setWithArtifact(boolean withArtifact) {
        this.withArtifact = withArtifact;
    }

    public String getPolicy() {
        return policy;
    }

    public void setPolicy(String policy) {
        this.policy = policy;
    }
}
