package io.jenkins.plugins.har.cli;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.EnvironmentSpecific;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.NodeSpecific;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolInstaller;
import hudson.tools.ToolProperty;
import io.jenkins.plugins.har.cli.callables.HarnessCliGitHubInstaller;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a Harness CLI (hc) tool installation in Jenkins.
 * <p>
 * Admins configure this under Manage Jenkins → Tools → Harness CLI installations.
 * The installation directory is exposed as the {@value #HARNESS_CLI_PATH} environment variable
 * so that {@link HcStep} can locate the binary at runtime.
 */
public class HarnessCliInstallation extends ToolInstallation
        implements NodeSpecific<HarnessCliInstallation>, EnvironmentSpecific<HarnessCliInstallation> {

    /** Environment variable set to the directory that contains the hc binary. */
    public static final String HARNESS_CLI_PATH = "HARNESS_CLI_PATH";

    @DataBoundConstructor
    public HarnessCliInstallation(String name, String home, List<? extends ToolProperty<?>> properties) {
        super(name, home, properties);
    }

    @Override
    public HarnessCliInstallation forEnvironment(EnvVars environment) {
        return new HarnessCliInstallation(getName(), environment.expand(getHome()), getProperties().toList());
    }

    @Override
    public HarnessCliInstallation forNode(@NonNull Node node, TaskListener log) throws IOException, InterruptedException {
        return new HarnessCliInstallation(getName(), translateFor(node, log), getProperties().toList());
    }

    @Override
    public void buildEnvVars(EnvVars env) {
        String home = getHome();
        if (home == null) {
            return;
        }
        env.put(HARNESS_CLI_PATH, home);
    }

    @Symbol("harnessCli")
    @Extension
    public static final class DescriptorImpl extends ToolDescriptor<HarnessCliInstallation> {

        public DescriptorImpl() {
            super(HarnessCliInstallation.class);
            load();
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return "Harness CLI (hc)";
        }

        @Override
        public List<? extends ToolInstaller> getDefaultInstallers() {
            List<ToolInstaller> list = new ArrayList<>();
            list.add(new HarnessCliGitHubInstaller(""));
            return list;
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject o) throws FormException {
            super.configure(req, o);
            save();
            return true;
        }
    }
}
