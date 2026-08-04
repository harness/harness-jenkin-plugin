package io.jenkins.plugins.har;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.Secret;
import io.jenkins.plugins.har.cli.HarnessGlobalConfiguration;
import io.jenkins.plugins.har.cli.HcStep;
import io.jenkins.plugins.har.metadata.MetadataCollector;
import io.jenkins.plugins.har.metadata.MetadataProcessor;
import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.ThreadNameAction;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import javax.annotation.Nonnull;
import java.io.PrintStream;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pipeline step {@code publishHarnessBuildInfo}
 *
 * <p>Collects Jenkins build metadata via {@link MetadataCollector}, prints it as JSON to the
 * build log, and adds a sidebar link that opens the Harness Artifact Registry page in a browser.
 *
 * <p>Example usage in a Declarative Pipeline:
 * <pre>{@code
 * stage('Publish build info') {
 *     steps {
 *         publishHarnessBuildInfo registry: 'rpm-test37'
 *     }
 * }
 * }</pre>
 *
 * <p>The {@code registry} parameter is optional. When omitted the link points to the top-level
 * Harness Artifact Registry module for the configured account.
 */
public class HarnessBuildInfoStep extends Step {

    private String registry;

    @DataBoundConstructor
    public HarnessBuildInfoStep() {
    }

    public String getRegistry() {
        return registry;
    }

    @DataBoundSetter
    public void setRegistry(String registry) {
        this.registry = StringUtils.trimToEmpty(registry);
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(registry, context);
    }

    // -------------------------------------------------------------------------

    public static class Execution extends SynchronousNonBlockingStepExecution<Void> {

        private static final long serialVersionUID = 1L;

        private final String registry;

        protected Execution(String registry, @Nonnull StepContext context) {
            super(context);
            this.registry = registry;
        }

        @Override
        protected Void run() throws Exception {
            Run<?, ?>    run      = getContext().get(Run.class);
            TaskListener listener = getContext().get(TaskListener.class);
            EnvVars      env      = getContext().get(EnvVars.class);
            FlowNode     flowNode = getContext().get(FlowNode.class);
            Computer     computer = getContext().get(Computer.class);
            PrintStream  log      = listener.getLogger();

            // ----------------------------------------------------------------
            // Resolve Harness config
            // ----------------------------------------------------------------
            HarnessGlobalConfiguration config = HarnessGlobalConfiguration.get();
            String apiUrl    = config != null ? StringUtils.defaultIfBlank(config.getApiUrl(), Constants.DEFAULT_BASE_URL) : Constants.DEFAULT_BASE_URL;
            String apiToken  = config != null ? Secret.toString(config.getApiToken()) : "";
            String orgId     = config != null ? config.getOrgId()     : "";
            String projectId = config != null ? config.getProjectId() : "";
            String accountId = HcStep.Execution.extractAccountIdFromToken(apiToken);

            // ----------------------------------------------------------------
            // Build the Harness Artifact Registry URL
            // ----------------------------------------------------------------
            String artifactUrl = buildArtifactUrl(apiUrl, accountId, orgId, projectId, registry);
            log.println("[publishHarnessBuildInfo] Harness Artifact Registry: " + artifactUrl);

            // ----------------------------------------------------------------
            // Collect metadata
            // ----------------------------------------------------------------
            String stageName = findEnclosingStage(flowNode);
            String nodeName  = "unknown";
            String labels    = "";
            if (computer != null) {
                nodeName = StringUtils.defaultIfEmpty(computer.getName(), "built-in");
                Node node = computer.getNode();
                if (node != null) {
                    labels = node.getAssignedLabels().stream()
                            .map(Object::toString)
                            .collect(Collectors.joining(" "));
                }
            }

            String metadataJson = new MetadataCollector()
                    .addPluginVersion(Constants.PLUGIN_SHORT_NAME)
                    .addJenkinsVersion()
                    .addJob(run)
                    .addTrigger(run)
                    .addPipeline(stageName, "publishHarnessBuildInfo")
                    .addAgent(nodeName, labels)
                    .addGit(env)
                    .addSystem()
                    .toJson();

            new MetadataProcessor().printMetaDataToRunEnv(log, metadataJson);

            //TODO we can submitt our metadata via some API
            //TODO which just stored it somewhere and return its URL , that we can use to submit below

            // ----------------------------------------------------------------
            // Add sidebar badge so the link appears next to this build run
            // ----------------------------------------------------------------
            String effectiveRegistry = StringUtils.defaultIfBlank(registry, "artifact-registry");
            run.addAction(new HarnessBuildBadgeAction(artifactUrl, effectiveRegistry));
            log.println("[publishHarnessBuildInfo] Build info published. Link added to build sidebar.");

            return null;
        }

        /**
         * Constructs the Harness Artifact Registry URL.
         * Format: {apiUrl}/ng/account/{accountId}/module/har/orgs/{orgId}/projects/{projectId}/registries/{registry}
         * Falls back to shorter paths when org, project or registry are not provided.
         */
        static String buildArtifactUrl(String apiUrl, String accountId,
                                        String orgId, String projectId, String registry) {
            String base = StringUtils.stripEnd(apiUrl, "/");

            if (StringUtils.isBlank(accountId)) {
                return base;
            }

            StringBuilder url = new StringBuilder(base)
                    .append("/ng/account/").append(accountId)
                    .append("/module/har");

            if (StringUtils.isNotBlank(orgId)) {
                url.append("/orgs/").append(orgId);
                if (StringUtils.isNotBlank(projectId)) {
                    url.append("/projects/").append(projectId);
                    if (StringUtils.isNotBlank(registry)) {
                        url.append("/registries/").append(registry);
                    }
                }
            }

            return url.toString();
        }

        private static String findEnclosingStage(FlowNode node) {
            FlowNode current = node;
            while (current != null) {
                LabelAction label = current.getAction(LabelAction.class);
                if (label != null && !(label instanceof ThreadNameAction)) {
                    return label.getDisplayName();
                }
                current = current.getParents().isEmpty() ? null : current.getParents().get(0);
            }
            return "";
        }
    }

    // -------------------------------------------------------------------------

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "publishHarnessBuildInfo";
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return "Publish Harness Build Info";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class, TaskListener.class, EnvVars.class);
        }
    }
}
