package io.jenkins.plugins.har;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Node;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ArgumentListBuilder;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import io.jenkins.plugins.har.cli.HarnessCliInstallation;
import io.jenkins.plugins.har.cli.HarnessCliLoginTracker;
import io.jenkins.plugins.har.cli.HarnessGlobalConfiguration;
import io.jenkins.plugins.har.cli.HcStep;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Build step for running Harness CLI (hc) commands in Freestyle jobs.
 *
 * <p>Mirrors what the {@code hc(...)} Pipeline step does for Declarative/Scripted pipelines.
 * Auto-runs {@code hc auth login} once per build using credentials from
 * <b>Manage Jenkins → Configure System → Harness CLI Configuration</b>.
 *
 * <p>Example commands:
 * <pre>
 *   hc version
 *   hc artifact push rpm my-repo /path/to/file.rpm
 *   hc auth status
 * </pre>
 */
public class HarnessCliBuilder extends Builder {

    private String command;
    private String harnessCliInstallation;

    @DataBoundConstructor
    public HarnessCliBuilder(String command) {
        this.command = command;
    }

    public String getCommand() {
        return command;
    }

    public String getHarnessCliInstallation() {
        return harnessCliInstallation;
    }

    @DataBoundSetter
    public void setCommand(String command) {
        this.command = command;
    }

    @DataBoundSetter
    public void setHarnessCliInstallation(String harnessCliInstallation) {
        this.harnessCliInstallation = harnessCliInstallation;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws InterruptedException, IOException {

        FilePath workspace = build.getWorkspace();
        if (workspace == null) {
            listener.error("[hc] Workspace is null");
            return false;
        }

        EnvVars env = build.getEnvironment(listener);

        // Apply tool installation env vars (sets HARNESS_CLI_PATH) if an installation is selected
        if (StringUtils.isNotBlank(harnessCliInstallation)) {
            HarnessCliInstallation installation = getInstallation();
            if (installation != null) {
                Node node = build.getBuiltOn();
                if (node != null) {
                    installation = installation.forNode(node, listener);
                }
                installation = installation.forEnvironment(env);
                installation.buildEnvVars(env);
            }
        }

        workspace.mkdirs();

        if (StringUtils.isBlank(command)) {
            listener.error("[hc] No command provided");
            return false;
        }

        boolean isWindows   = !launcher.isUnix();
        String hcBinaryPath = HcStep.Execution.getHcCliPath(env, isWindows);
        listener.getLogger().println("[hc] Using binary: " + hcBinaryPath);

        // Auto-login once per build — synchronized so parallel Freestyle steps don't race
        synchronized (build) {
            if (build.getAction(HarnessCliLoginTracker.class) == null) {
                try {
                    performLogin(launcher, workspace, env, hcBinaryPath, isWindows, listener);
                    build.addAction(new HarnessCliLoginTracker());
                } catch (IOException e) {
                    String msg = ExceptionUtils.getRootCauseMessage(e);
                    listener.error("[hc] Login failed: " + msg);
                    if (msg != null && (msg.contains("No such file or directory")
                            || msg.contains("Cannot run program")
                            || msg.contains("error: 2"))) {
                        listener.error("[hc] Harness CLI binary not found at: " + hcBinaryPath);
                        listener.error("     → Go to Manage Jenkins → Tools → Harness CLI installations and add an installation.");
                        listener.error("     → Or install the hc binary on the system PATH of the agent.");
                    }
                    return false;
                }
            }
        }

        // Strip leading 'hc' prefix if the user typed the full command (e.g. "hc artifact push ...")
        String trimmed = command.trim();
        String[] fullArgs = StringUtils.split(trimmed);
        String[] args;
        if (fullArgs.length > 0 && fullArgs[0].equalsIgnoreCase("hc")) {
            args = new String[fullArgs.length - 1];
            System.arraycopy(fullArgs, 1, args, 0, args.length);
        } else {
            args = fullArgs;
        }

        ArgumentListBuilder builder = new ArgumentListBuilder();
        builder.add(hcBinaryPath).add(args);
        if (isWindows) {
            builder = builder.toWindowsCommand();
        }

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            int exitCode = launcher.launch()
                    .envs(env)
                    .pwd(workspace)
                    .cmds(builder)
                    .stdout(outputStream)
                    .stderr(listener.getLogger())
                    .join();

            String output = outputStream.toString(StandardCharsets.UTF_8);
            listener.getLogger().print(output);

            if (exitCode != 0) {
                listener.error("[hc] Command failed with exit code " + exitCode);
                return false;
            }
            return true;

        } catch (IOException e) {
            if (e.getMessage() != null && (e.getMessage().contains("No such file or directory")
                    || e.getMessage().contains("Cannot run program"))) {
                listener.error("[hc] Harness CLI binary not found.");
                listener.error("     → Go to Manage Jenkins → Tools → Harness CLI installations and add an installation.");
                listener.error("     → Or set the hc binary on the system PATH of the agent.");
                listener.error("Error: " + ExceptionUtils.getRootCauseMessage(e));
            } else {
                listener.error("[hc] Failed to execute command: " + ExceptionUtils.getRootCauseMessage(e));
            }
            return false;
        }
    }

    /**
     * Runs {@code hc auth login} using credentials from {@link HarnessGlobalConfiguration}.
     * The API token is masked in logs via {@link ArgumentListBuilder#addMasked}.
     */
    private static void performLogin(Launcher launcher, FilePath workspace, EnvVars env,
                                     String hcBinaryPath, boolean isWindows,
                                     BuildListener listener) throws IOException, InterruptedException {

        HarnessGlobalConfiguration config = HarnessGlobalConfiguration.get();
        if (config == null) {
            listener.getLogger().println("[hc] WARNING: HarnessGlobalConfiguration not found — skipping auto-login.");
            return;
        }

        String apiUrl   = config.getApiUrl();
        String apiToken = Secret.toString(config.getApiToken());

        if (StringUtils.isBlank(apiToken)) {
            listener.getLogger().println(
                    "[hc] WARNING: Harness API Token is not configured. "
                    + "Go to Manage Jenkins → Configure System → Harness CLI Configuration.");
            return;
        }

        String effectiveUrl = StringUtils.defaultIfBlank(apiUrl, Constants.DEFAULT_BASE_URL);
        listener.getLogger().println("[hc] Running 'hc auth login' against: " + effectiveUrl);

        String accountId = HcStep.Execution.extractAccountIdFromToken(apiToken);
        if (StringUtils.isBlank(accountId)) {
            throw new IOException(
                    "[hc] Could not extract Account ID from the API token. "
                    + "Expected PAT format: pat.<AccountID>.<random>.<random>");
        }

        ArgumentListBuilder builder = new ArgumentListBuilder();
        builder.add(hcBinaryPath)
               .add("auth").add("login")
               .add("--api-url=" + effectiveUrl);
        builder.addMasked("--api-token=" + apiToken);
        builder.add("--account=" + accountId)
               .add("--non-interactive");

        String orgId = config.getOrgId();
        if (StringUtils.isNotBlank(orgId)) {
            builder.add("--org=" + orgId);
        }
        String projectId = config.getProjectId();
        if (StringUtils.isNotBlank(projectId)) {
            builder.add("--project=" + projectId);
        }

        if (isWindows) {
            builder = builder.toWindowsCommand();
        }

        int exitCode = launcher.launch()
                .envs(env)
                .pwd(workspace)
                .cmds(builder)
                .stdout(listener.getLogger())
                .stderr(listener.getLogger())
                .join();

        if (exitCode != 0) {
            throw new IOException(
                    "'hc auth login' failed with exit code " + exitCode
                    + ". Check credentials under Manage Jenkins → Configure System → Harness CLI Configuration.");
        }
        listener.getLogger().println("[hc] Login successful.");
    }

    private HarnessCliInstallation getInstallation() {
        if (harnessCliInstallation == null) {
            return null;
        }
        HarnessCliInstallation[] installations = ((DescriptorImpl) getDescriptor()).getInstallations();
        if (installations == null) {
            return null;
        }
        for (HarnessCliInstallation inst : installations) {
            if (inst != null && harnessCliInstallation.equals(inst.getName())) {
                return inst;
            }
        }
        return null;
    }

    @Extension
    @Symbol("harnessCliRun")
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @Nonnull
        @Override
        public String getDisplayName() {
            return "Run Harness CLI (hc) command";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        public HarnessCliInstallation[] getInstallations() {
            return jenkins.model.Jenkins.get()
                    .getDescriptorByType(HarnessCliInstallation.DescriptorImpl.class)
                    .getInstallations();
        }

        public ListBoxModel doFillHarnessCliInstallationItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("(Use hc from system PATH)", "");
            for (HarnessCliInstallation inst : getInstallations()) {
                items.add(inst.getName(), inst.getName());
            }
            return items;
        }
    }
}
