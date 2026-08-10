package io.jenkins.plugins.har;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.har.cli.HarnessCliInstallation;
import jenkins.tasks.SimpleBuildWrapper;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import javax.annotation.Nonnull;
import java.io.IOException;

/**
 * Build Environment wrapper that sets up Harness CLI (hc) for the entire Freestyle build.
 *
 * <p>Appears as the <b>"Set up Harness CLI environment"</b> checkbox under
 * <b>Build Environment</b> in a Freestyle job configuration.
 *
 * <p>When enabled, it sets the {@value HarnessCliInstallation#HARNESS_CLI_PATH} environment
 * variable for every build step and shell script in the build — so you can call {@code hc}
 * directly in any Execute Shell step without specifying the full path.
 *
 * <p>This is the Freestyle equivalent of the Pipeline directive:
 * <pre>{@code
 * tools { harnessCli 'harness-cli' }
 * }</pre>
 */
public class HarnessCliWrapper extends SimpleBuildWrapper {

    private String harnessCliInstallation;

    @DataBoundConstructor
    public HarnessCliWrapper() {
    }

    public String getHarnessCliInstallation() {
        return harnessCliInstallation;
    }

    @DataBoundSetter
    public void setHarnessCliInstallation(String harnessCliInstallation) {
        this.harnessCliInstallation = harnessCliInstallation;
    }

    @Override
    public void setUp(Context context, Run<?, ?> build, FilePath workspace,
                      Launcher launcher, TaskListener listener, EnvVars initialEnvironment)
            throws IOException, InterruptedException {

        if (harnessCliInstallation == null || harnessCliInstallation.isEmpty()) {
            listener.getLogger().println("[Harness CLI] No installation selected — using hc from system PATH.");
            return;
        }

        HarnessCliInstallation installation = getInstallation();
        if (installation == null) {
            listener.error("[Harness CLI] Installation '" + harnessCliInstallation + "' not found. "
                    + "Check Manage Jenkins → Tools → Harness CLI installations.");
            return;
        }

        // Resolve the installation for the current build node
        hudson.model.Node node = workspaceToNode(workspace);
        if (node != null) {
            installation = installation.forNode(node, listener);
        }
        installation = installation.forEnvironment(initialEnvironment);

        // Push env vars (HARNESS_CLI_PATH) into the build environment for ALL steps
        EnvVars envVars = new EnvVars();
        installation.buildEnvVars(envVars);
        for (java.util.Map.Entry<String, String> entry : envVars.entrySet()) {
            context.env(entry.getKey(), entry.getValue());
        }

        listener.getLogger().println("[Harness CLI] Using installation: " + harnessCliInstallation);
        listener.getLogger().println("[Harness CLI] HARNESS_CLI_PATH set to: "
                + envVars.get(HarnessCliInstallation.HARNESS_CLI_PATH));
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

    private static hudson.model.Node workspaceToNode(FilePath workspace) {
        if (workspace == null) {
            return null;
        }
        hudson.model.Computer computer = workspace.toComputer();
        if (computer == null) {
            return null;
        }
        return computer.getNode();
    }

    @Extension
    @Symbol("harnessCliEnv")
    public static final class DescriptorImpl extends BuildWrapperDescriptor {

        @Nonnull
        @Override
        public String getDisplayName() {
            return "Set up Harness CLI environment";
        }

        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            // Exclude Matrix projects — CLI installations may differ across nodes
            try {
                Class<?> matrixProjectClass = Class.forName("hudson.matrix.MatrixProject");
                if (matrixProjectClass.isInstance(item)) {
                    return false;
                }
            } catch (ClassNotFoundException e) {
                // matrix-project plugin not installed; nothing to exclude
            }
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
