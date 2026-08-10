package io.jenkins.plugins.har.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import hudson.EnvVars;
import hudson.Plugin;
import hudson.model.Cause;
import hudson.model.Result;
import hudson.model.Run;
import jenkins.model.Jenkins;

/**
 * Builds the metadata JSON that the plugin sends/logs.
 * Sections are added one at a time (job, trigger, git, agent, ...).
 */
public class MetadataCollector {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ObjectNode root = MAPPER.createObjectNode();

    public MetadataCollector addJob(Run<?, ?> run) {
        if (run == null) {
            return this;
        }
        ObjectNode job = root.putObject("job");
        job.put("name", run.getParent().getFullName());
        job.put("buildNumber", run.getNumber());
        job.put("buildId", run.getId());
        job.put("displayName", run.getDisplayName());
        job.put("fullDisplayName", run.getFullDisplayName());
        job.put("url", run.getUrl());
        job.put("startTimeMillis", run.getStartTimeInMillis());
        job.put("durationMillis", run.getDuration());

        Result result = run.getResult();
        job.put("result", result == null ? "IN_PROGRESS" : result.toString());
        return this;
    }

    public MetadataCollector addTrigger(Run<?, ?> run) {
        if (run == null) {
            return this;
        }
        ObjectNode trigger = root.putObject("trigger");
        ArrayNode causes = trigger.putArray("causes");
        for (Cause cause : run.getCauses()) {
            ObjectNode c = causes.addObject();
            c.put("type", cause.getClass().getSimpleName());
            c.put("description", cause.getShortDescription());
            if (cause instanceof Cause.UserIdCause userCause) {
                c.put("userId", userCause.getUserId());
                c.put("userName", userCause.getUserName());
            }
        }
        return this;
    }

    public MetadataCollector addPipeline(String stage, String stepName) {
        ObjectNode pipeline = root.putObject("pipeline");
        pipeline.put("stage", stage);
        pipeline.put("step", stepName);
        return this;
    }

    public MetadataCollector addAgent(String nodeName, String labels) {
        ObjectNode agent = root.putObject("agent");
        agent.put("node", nodeName);
        agent.put("labels", labels);
        return this;
    }

    public MetadataCollector addSystem() {
        ObjectNode system = root.putObject("system");
        system.put("java", System.getProperty("java.version"));
        system.put("os", System.getProperty("os.name"));
        system.put("arch", System.getProperty("os.arch"));
        return this;
    }

    public MetadataCollector addGit(EnvVars env) {
        if (env == null) {
            return this;
        }
        ObjectNode git = root.putObject("git");
        git.put("repo", env.get("GIT_URL", ""));
        git.put("branch", env.get("GIT_BRANCH", ""));
        git.put("commit", env.get("GIT_COMMIT", ""));
        return this;
    }

    public MetadataCollector addPluginVersion(String pluginShortName) {
        String version = "unknown";
        try {
            Jenkins jenkins = Jenkins.getInstanceOrNull();
            if (jenkins != null) {
                Plugin plugin = jenkins.getPlugin(pluginShortName);
                if (plugin != null) {
                    version = plugin.getWrapper().getVersion();
                }
            }
        } catch (Exception ignored) {
        }
        root.put("pluginVersion", version);
        return this;
    }

    public MetadataCollector addJenkinsVersion() {
        String version = "unknown";
        try {
            if (Jenkins.getVersion() != null) {
                version = Jenkins.getVersion().toString();
            }
        } catch (Exception ignored) {
        }
        root.put("jenkinsVersion", version);
        return this;
    }

    public MetadataCollector addRequest(String traceId, int timeoutMs, int retryCount, int payloadSize) {
        ObjectNode req = root.putObject("request");
        req.put("traceId", traceId);
        req.put("timeout", timeoutMs);
        req.put("retryCount", retryCount);
        req.put("payloadSize", payloadSize);
        return this;
    }

    public MetadataCollector addResponse(int status, String responseBody, long latencyMs) {
        ObjectNode resp = root.putObject("response");
        resp.put("status", status);
        resp.put("latencyMs", latencyMs);
        resp.put("executionId", extractExecutionId(responseBody));
        return this;
    }

    private static String extractExecutionId(String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            // Harness v1 response shape varies; check a few common locations.
            for (String path : new String[]{"execution_details_id", "executionId", "execution_id"}) {
                JsonNode n = root.get(path);
                if (n != null && !n.isNull()) {
                    return n.asText();
                }
            }
            JsonNode data = root.get("data");
            if (data != null) {
                for (String path : new String[]{"planExecution", "executionId", "execution_id"}) {
                    JsonNode n = data.get(path);
                    if (n != null) {
                        JsonNode id = n.isObject() ? n.get("uuid") : n;
                        if (id != null && !id.isNull()) {
                            return id.asText();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    public ObjectNode build() {
        return root;
    }

    public String toJson() {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            return root.toString();
        }
    }
}
