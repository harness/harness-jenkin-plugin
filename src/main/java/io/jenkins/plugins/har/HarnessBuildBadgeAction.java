package io.jenkins.plugins.har;

import hudson.model.Action;
import org.kohsuke.stapler.StaplerResponse2;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.io.IOException;

/**
 * A sidebar action added to a Jenkins build when {@code publishHarnessBuildInfo} runs.
 * Clicking the link in the sidebar redirects the browser to the Harness Artifact Registry page.
 */

public class HarnessBuildBadgeAction implements Action {

    private final String artifactUrl;
    private final String registryName;

    public HarnessBuildBadgeAction(String artifactUrl, String registryName) {
        this.artifactUrl = artifactUrl;
        this.registryName = registryName;
    }

    public String getArtifactUrl() {
        return artifactUrl;
    }

    public String getRegistryName() {
        return registryName;
    }

    @CheckForNull
    @Override
    public String getIconFileName() {
        return "symbol-harness plugin-harness-upload";
    }

    @Override
    public String getDisplayName() {
        return "Harness Artifact: " + registryName;
    }

    @Override
    public String getUrlName() {
        return "harness-artifact-info";
    }

    /**
     * Stapler calls this when the user clicks the sidebar link.
     * Redirects the browser to the Harness Artifact Registry page.
     */
    @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"})
    public void doIndex(StaplerResponse2 rsp) throws IOException {
        rsp.sendRedirect2(artifactUrl);
    }
}
