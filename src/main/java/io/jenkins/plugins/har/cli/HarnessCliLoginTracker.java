package io.jenkins.plugins.har.cli;

import hudson.model.InvisibleAction;

/**
 * A build action whose <em>presence</em> on a {@code Run} signals that
 * {@code hc auth login} has already been executed for this build.
 *
 * <p>{@link HcStep} adds this action after a successful login and checks for it
 * before every subsequent {@code hc} invocation in the same build, preventing
 * redundant login calls when multiple {@code hc} steps appear in a pipeline.
 */
public class HarnessCliLoginTracker extends InvisibleAction {
    // Marker only — no state needed.
}
