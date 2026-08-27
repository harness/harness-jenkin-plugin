package io.jenkins.plugins.har.cli;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import hudson.util.Secret;
import io.jenkins.plugins.har.Constants;
import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static io.jenkins.plugins.har.cli.HarnessCliInstallation.HARNESS_CLI_PATH;

/**
 * Pipeline step {@code hc(...)} that runs the Harness CLI binary.
 *
 * <p>Before the <em>first</em> {@code hc} invocation in a build the step automatically runs
 * {@code hc auth login --username <u> --password <token>} using the credentials configured
 * under <b>Manage Jenkins → Configure System → Harness CLI Configuration</b>.
 * Subsequent {@code hc} steps in the same build skip the login (tracked via
 * {@link HarnessCliLoginTracker}).
 *
 * <p>Example Declarative Pipeline:
 * <pre>{@code
 * pipeline {
 *     agent any
 *     tools { harnessCli 'hc-default' }
 *     stages {
 *         stage('Deploy') {
 *             steps {
 *                 hc 'artifact push generic my-repo build/output.jar'
 *             }
 *         }
 *     }
 * }
 * }</pre>
 */
public class HcStep extends Step {

    private final String[] args;

    @DataBoundConstructor
    public HcStep(Object args) {
        if (args instanceof List) {
            //noinspection unchecked
            this.args = ((List<String>) args).toArray(String[]::new);
            return;
        }
        this.args = StringUtils.split(args.toString());
    }

    public String[] getArgs() {
        return args;
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(args, context);
    }

    // -------------------------------------------------------------------------

    public static class Execution extends SynchronousNonBlockingStepExecution<String> {

        private static final long serialVersionUID = 1L;

        private final String[] args;

        protected Execution(String[] args, @NonNull StepContext context) {
            super(context);
            this.args = args;
        }

        @Override
        protected String run() throws Exception {
            Launcher     launcher  = getContext().get(Launcher.class);
            FilePath     workspace = getContext().get(FilePath.class);
            TaskListener listener  = getContext().get(TaskListener.class);
            EnvVars      env       = getContext().get(EnvVars.class);
            Run<?, ?>    run       = getContext().get(Run.class);

            if (workspace != null) {
                workspace.mkdirs();
            }

            boolean isWindows    = !launcher.isUnix();
            String  hcBinaryPath = getHcCliPath(env, isWindows);
            listener.getLogger().println("[hc] Using binary: " + hcBinaryPath);

            // ------------------------------------------------------------------
            // Auto-login: run 'hc auth login' once per build.
            // Synchronise on the Run object so parallel pipeline stages don't
            // race to log in simultaneously (same pattern as JFrog's config step).
            // ------------------------------------------------------------------
            synchronized (run) {
                if (run.getAction(HarnessCliLoginTracker.class) == null) {
                    performLogin(launcher, workspace, env, hcBinaryPath, isWindows, listener);
                    run.addAction(new HarnessCliLoginTracker());
                }
            }

            // ------------------------------------------------------------------
            // Run the actual hc command
            // ------------------------------------------------------------------
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
                    throw new RuntimeException("'hc' command failed with exit code " + exitCode);
                }
                return output;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("Failed to execute 'hc' command: " + e.getMessage(), e);
            }
        }

        /**
         * Runs {@code hc auth login --username <u> --password <token>} using credentials
         * from {@link HarnessGlobalConfiguration}.
         * The API token is masked in Jenkins logs via {@link ArgumentListBuilder#addMasked}.
         */
        private static void performLogin(Launcher launcher, FilePath workspace, EnvVars env,
                                         String hcBinaryPath, boolean isWindows,
                                         TaskListener listener)
                throws IOException, InterruptedException {

            HarnessGlobalConfiguration config = HarnessGlobalConfiguration.get();
            if (config == null) {
                listener.getLogger().println(
                        "[hc] WARNING: HarnessGlobalConfiguration not found — skipping auto-login.");
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

            String effectiveUrl = StringUtils.defaultIfBlank(apiUrl,  Constants.DEFAULT_BASE_URL);
            listener.getLogger().println("[hc] Running 'hc auth login' against: " + effectiveUrl);

            // Derive accountID from the PAT token: format is pat.<AccountID>.<random>.<random>
            String accountId = extractAccountIdFromToken(apiToken);
            if (StringUtils.isBlank(accountId)) {
                throw new IOException(
                        "[hc] Could not extract Account ID from the API token. "
                        + "Expected PAT format: pat.<AccountID>.<random>.<random>");
            }

            // Flag names confirmed from harness-cli/cmd/auth/login.go source.
            // --non-interactive is required to prevent the CLI from hanging waiting for stdin.
            ArgumentListBuilder builder = new ArgumentListBuilder();
            builder.add(hcBinaryPath)
                   .add("auth")
                   .add("login")
                   .add("--api-url=" + effectiveUrl);
            builder.addMasked("--api-token=" + apiToken); // addMasked returns void; token never appears in plain text in logs
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
                        + ". Check the credentials under Manage Jenkins → Configure System → Harness CLI Configuration.");
            }
            listener.getLogger().println("[hc] Login successful.");
        }

        /**
         * PAT format: {@code pat.<AccountID>.<random>.<random>}
         * Returns the AccountID segment, or an empty string if the token is malformed.
         */
        public static String extractAccountIdFromToken(String token) {
            if (StringUtils.isBlank(token)) {
                return "";
            }
            String[] parts = token.split("\\.", 3);
            return parts.length >= 2 ? parts[1] : "";
        }

        /**
         * Resolves the full path to the hc binary.
         * Uses {@value HarnessCliInstallation#HARNESS_CLI_PATH} when set (via the tool installer),
         * or falls back to just the binary name so the OS PATH is searched.
         */
        public static String getHcCliPath(EnvVars env, boolean isWindows) {
            String binaryName = HarnessOsUtils.getBinaryName(isWindows);
            String cliDir     = env.get(HARNESS_CLI_PATH, "");
            if (StringUtils.isBlank(cliDir)) {
                return binaryName; // rely on system PATH
            }
            String sep  = isWindows ? "\\" : "/";
            String base = cliDir.endsWith("/") || cliDir.endsWith("\\")
                    ? cliDir.substring(0, cliDir.length() - 1)
                    : cliDir;
            String fullPath = base + sep + binaryName;
            return isWindows ? fullPath.replace('/', '\\') : fullPath.replace('\\', '/');
        }
    }

    // -------------------------------------------------------------------------

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "hc";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Run Harness CLI (hc) command";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Launcher.class, FilePath.class, TaskListener.class, EnvVars.class, Run.class);
        }
    }
}
