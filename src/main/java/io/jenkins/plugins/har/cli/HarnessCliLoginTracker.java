package io.jenkins.plugins.har.cli;

import hudson.model.InvisibleAction;

/**
 * A build action whose <em>presence</em> on a {@code Run} signals that
 * {@code hc auth login} has already been executed for this build.
 *
 * <p>{@link HcStep} adds this action after a successful login and checks for it
 * before every subsequent {@code hc} invocation in the same build, preventing
 * redundant login calls when multiple {@code hc} steps appear in a pipeline.
 *
 * <p>Also records the resolved binary path so that logout can use the same
 * binary at build completion without needing to re-resolve tool installations.
 */
public class HarnessCliLoginTracker extends InvisibleAction {

    private final String hcBinaryPath;
    /** Remote workspace path as seen by the agent at login time. */
    private final String workspacePath;
    /** Name of the node/agent where login ran (empty string = built-in node). */
    private final String nodeName;

    /** Transient — not persisted; reset to false after a Jenkins restart (acceptable). */
    private transient volatile boolean loggedOut;

    public HarnessCliLoginTracker(String hcBinaryPath, String workspacePath, String nodeName) {
        this.hcBinaryPath  = hcBinaryPath;
        this.workspacePath = workspacePath;
        this.nodeName      = nodeName;
    }

    public String getHcBinaryPath()  { return hcBinaryPath; }
    public String getWorkspacePath() { return workspacePath; }
    public String getNodeName()      { return nodeName; }

    /**
     * Claims the logout responsibility. Returns {@code true} exactly once;
     * subsequent calls return {@code false} so that neither the Disposer nor
     * the RunListener runs logout twice on the same build.
     */
    public synchronized boolean markLoggedOut() {
        if (loggedOut) {
            return false;
        }
        loggedOut = true;
        return true;
    }
}
