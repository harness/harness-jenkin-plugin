package io.jenkins.plugins.har.cli;

import hudson.Extension;
import hudson.util.FormValidation;
import hudson.util.Secret;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Global Jenkins configuration for the Harness CLI plugin.
 * Appears under <b>Manage Jenkins → Configure System → Harness CLI Configuration</b>.
 *
 * <p>These credentials are used by the {@link HcStep} pipeline step to automatically
 * run {@code hc auth login} before the first {@code hc} command in each build,
 * so individual pipeline steps do not need to handle authentication themselves.
 *
 * <p>TODO: Open Manage Jenkins → Configure System and fill in:
 * <ul>
 *   <li>API URL      — Harness API endpoint (default: https://app.harness.io)</li>
 *   <li>API Token    — a Personal Access Token (PAT) from Harness (mandatory)</li>
 *   <li>Org ID       — Harness organisation ID (optional)</li>
 *   <li>Project ID   — Harness project ID (optional)</li>
 * </ul>
 */
@Extension
@Symbol("harnessCliConfig")
public class HarnessGlobalConfiguration extends GlobalConfiguration {

    // TODO: Fill these in via Manage Jenkins → Configure System → Harness CLI Configuration
    private String apiUrl    = "https://app.harness.io";
    private Secret apiToken  = Secret.fromString("");
    private String orgId     = "";
    private String projectId = "";

    public HarnessGlobalConfiguration() {
        load();
    }

    /** Returns the singleton instance registered by Jenkins. */
    public static HarnessGlobalConfiguration get() {
        return GlobalConfiguration.all().get(HarnessGlobalConfiguration.class);
    }

    // -------------------------------------------------------------------------
    // Getters (used by Jelly and by HcStep at runtime)
    // -------------------------------------------------------------------------

    public String getApiUrl() {
        return apiUrl;
    }

    public Secret getApiToken() {
        return apiToken;
    }

    public String getOrgId() {
        return orgId;
    }

    public String getProjectId() {
        return projectId;
    }

    // -------------------------------------------------------------------------
    // Setters — DataBoundSetter so Stapler binds the form fields
    // -------------------------------------------------------------------------

    @DataBoundSetter
    public void setApiUrl(String apiUrl) {
        this.apiUrl = StringUtils.defaultIfBlank(apiUrl, "https://app.harness.io");
    }

    @DataBoundSetter
    public void setApiToken(Secret apiToken) {
        this.apiToken = apiToken;
    }

    @DataBoundSetter
    public void setOrgId(String orgId) {
        this.orgId = StringUtils.trimToEmpty(orgId);
    }

    @DataBoundSetter
    public void setProjectId(String projectId) {
        this.projectId = StringUtils.trimToEmpty(projectId);
    }

    // -------------------------------------------------------------------------
    // Form validation
    // -------------------------------------------------------------------------

    @POST
    @SuppressWarnings("unused")
    public FormValidation doCheckApiUrl(@QueryParameter String value) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (StringUtils.isBlank(value)) {
            return FormValidation.warning("API URL is required. Default: https://app.harness.io");
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            return FormValidation.error("API URL must start with http:// or https://");
        }
        return FormValidation.ok();
    }

    @POST
    @SuppressWarnings("unused")
    public FormValidation doCheckApiToken(@QueryParameter String value) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (StringUtils.isBlank(value)) {
            return FormValidation.warning("API Token is required for 'hc auth login'.");
        }
        return FormValidation.ok();
    }

    // -------------------------------------------------------------------------
    // Save
    // -------------------------------------------------------------------------

    @Override
    public boolean configure(StaplerRequest2 req, JSONObject json) throws FormException {
        req.bindJSON(this, json);
        save();
        return true;
    }

    @NonNull
    @Override
    public String getDisplayName() {
        return "Harness CLI Configuration";
    }
}
