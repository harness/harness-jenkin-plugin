package io.jenkins.plugins.har;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.Secret;
import io.jenkins.plugins.har.cli.HarnessGlobalConfiguration;
import io.jenkins.plugins.har.cli.HcStep;
import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import javax.annotation.Nonnull;
import java.io.IOException;

/**
 * Post-build action for Freestyle jobs that adds a Harness Artifact Registry sidebar link.
 *
 * <p>Mirrors what the {@code publishHarnessBuildInfo} Pipeline step does for
 * Declarative/Scripted pipelines. Reads Harness credentials from
 * <b>Manage Jenkins → Configure System → Harness CLI Configuration</b> and
 * adds a sidebar badge that links directly to the configured registry.
 *
 * <p>Example Freestyle configuration:
 * <ul>
 *   <li>Post-build Action → "Publish Harness Build Info"</li>
 *   <li>Registry: {@code rpm-jenkin} (optional)</li>
 * </ul>
 */
public class HarnessBuildInfoPublisher extends Notifier {

    private String registry;
    private boolean publishOnlyOnSuccess = true;

    @DataBoundConstructor
    public HarnessBuildInfoPublisher() {
    }

    public String getRegistry() {
        return registry;
    }

    public boolean isPublishOnlyOnSuccess() {
        return publishOnlyOnSuccess;
    }

    @DataBoundSetter
    public void setRegistry(String registry) {
        this.registry = StringUtils.trimToEmpty(registry);
    }

    @DataBoundSetter
    public void setPublishOnlyOnSuccess(boolean publishOnlyOnSuccess) {
        this.publishOnlyOnSuccess = publishOnlyOnSuccess;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws InterruptedException, IOException {

        Result buildResult = build.getResult();
        if (publishOnlyOnSuccess && buildResult != null && buildResult.isWorseThan(Result.SUCCESS)) {
            listener.getLogger().println(
                    "[publishHarnessBuildInfo] Skipping — build result is " + buildResult);
            return true;
        }

        EnvVars env = build.getEnvironment(listener);

        HarnessGlobalConfiguration config = HarnessGlobalConfiguration.get();
        String apiUrl    = config != null
                ? StringUtils.defaultIfBlank(config.getApiUrl(), Constants.DEFAULT_BASE_URL)
                : Constants.DEFAULT_BASE_URL;
        String apiToken  = config != null ? Secret.toString(config.getApiToken()) : "";
        String orgId     = config != null ? config.getOrgId()     : "";
        String projectId = config != null ? config.getProjectId() : "";
        String accountId = HcStep.Execution.extractAccountIdFromToken(apiToken);

        String artifactUrl = HarnessBuildInfoStep.Execution.buildArtifactUrl(
                apiUrl, accountId, orgId, projectId, registry);
        listener.getLogger().println(
                "[publishHarnessBuildInfo] Harness Artifact Registry: " + artifactUrl);

        String effectiveRegistry = StringUtils.defaultIfBlank(registry, "artifact-registry");
        build.addAction(new HarnessBuildBadgeAction(artifactUrl, effectiveRegistry));
        listener.getLogger().println(
                "[publishHarnessBuildInfo] Build info published. Link added to build sidebar.");
        return true;
    }

    @Extension
    @Symbol("harnessPublishBuildInfo")
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        @Nonnull
        @Override
        public String getDisplayName() {
            return "Publish Harness Build Info";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }
    }
}
