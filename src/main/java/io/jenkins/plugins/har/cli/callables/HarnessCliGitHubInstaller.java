package io.jenkins.plugins.har.cli.callables;

import hudson.Extension;
import hudson.FilePath;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolInstaller;
import hudson.tools.ToolInstallerDescriptor;
import io.jenkins.plugins.har.Constants;
import io.jenkins.plugins.har.cli.HarnessCliInstallation;
import io.jenkins.plugins.har.cli.HarnessOsUtils;
import jenkins.MasterToSlaveFileCallable;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.client.LaxRedirectStrategy;
import org.apache.http.util.EntityUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Downloads the Harness CLI (hc) binary from GitHub releases and installs it on Jenkins agents.
 *
 * <p>Release asset naming convention used by harness/harness-cli (e.g. v1.3.30):
 * <pre>
 *   hc_{version}_{os}_{arch}.tar.gz
 *
 *   OS   : linux | mac-os | windows
 *   Arch : x86_64 | arm64 | i386
 *
 *   e.g. hc_1.3.30_linux_x86_64.tar.gz
 *        hc_1.3.30_mac-os_arm64.tar.gz
 *        hc_1.3.30_windows_x86_64.tar.gz
 * </pre>
 *
 * When version is left blank the installer queries the GitHub Releases API
 * to determine the latest version tag.
 */
public class HarnessCliGitHubInstaller extends ToolInstaller {

    private static final long serialVersionUID = 1L;



    /** Minimum expected binary size – anything smaller is considered a failed download. */
    private static final long MIN_BINARY_SIZE_BYTES = 1024L * 1024L; // 1 MB

    private String version;

    @DataBoundConstructor
    public HarnessCliGitHubInstaller(String version) {
        super(null);
        this.version = StringUtils.trimToEmpty(version);
    }

    public String getVersion() {
        return version;
    }

    @DataBoundSetter
    public void setVersion(String version) {
        this.version = StringUtils.trimToEmpty(version);
    }

    // -------------------------------------------------------------------------

    @Override
    public FilePath performInstallation(ToolInstallation tool, Node node, TaskListener log)
            throws IOException, InterruptedException {

        FilePath toolHome = preferredLocation(tool, node);
        if (!toolHome.exists()) {
            toolHome.mkdirs();
        }

        boolean isWindows = !node.createLauncher(log).isUnix();
        String binaryName = HarnessOsUtils.getBinaryName(isWindows);
        FilePath cliPath  = toolHome.child(binaryName);

        // Detect OS + arch on the actual agent node
        String osInfo = toolHome.act(new MasterToSlaveFileCallable<String>() {
            private static final long serialVersionUID = 1L;
            @Override
            public String invoke(File f, VirtualChannel channel) throws IOException {
                return HarnessOsUtils.getOs() + "|" + HarnessOsUtils.getArch();
            }
        });
        String[] parts = osInfo.split("\\|", 2);
        String os   = parts[0];
        String arch = parts.length > 1 ? parts[1] : "x86_64";

        log.getLogger().println("[Harness CLI] Agent detected: os=" + os + " arch=" + arch);

        try (CloseableHttpClient httpClient = HttpClients.custom()
                .setRedirectStrategy(new LaxRedirectStrategy())
                .build()) {

            // Resolve the target version FIRST so we can do a version-aware cache check.
            String resolvedVersion = StringUtils.isBlank(version)
                    ? resolveLatestVersion(httpClient, log)
                    : stripLeadingV(version);

            // Skip download only when the binary exists AND the installed version matches.
            FilePath versionMarker = toolHome.child(".installed-version");
            if (cliPath.exists() && cliPath.length() > MIN_BINARY_SIZE_BYTES
                    && versionMarker.exists()
                    && resolvedVersion.equals(versionMarker.readToString().trim())) {
                log.getLogger().println("[Harness CLI] Already installed at " + cliPath.getRemote()
                        + " (v" + resolvedVersion + ") — skipping download.");
                return toolHome;
            }

            String downloadUrl = buildDownloadUrl(resolvedVersion, os, arch);
            log.getLogger().println("[Harness CLI] Downloading v" + resolvedVersion + ": " + downloadUrl);

            downloadAndExtract(httpClient, downloadUrl, toolHome, log);

            // Find the hc binary inside the extracted archive tree
            FilePath binary = findBinaryAfterExtraction(toolHome, binaryName, log);
            if (!isWindows) {
                binary.chmod(0755);
            }

            // Write the version marker so future runs can skip the download
            versionMarker.write(resolvedVersion, "UTF-8");

            log.getLogger().println("[Harness CLI] Installation complete: " + binary.getRemote()
                    + " (v" + resolvedVersion + ")");
        }

        return toolHome;
    }

    // -------------------------------------------------------------------------

    /**
     * Calls the GitHub Releases API to get the latest version tag and strips the leading 'v'.
     * e.g.  "v1.3.30" → "1.3.30"
     */
    private static String resolveLatestVersion(CloseableHttpClient httpClient, TaskListener log)
            throws IOException {
        log.getLogger().println("[Harness CLI] Querying GitHub API for latest release...");
        HttpGet request = new HttpGet(Constants.GITHUB_API_LATEST);
        request.setHeader("Accept", "application/vnd.github.v3+json");
        request.setHeader("User-Agent", "harness-jenkins-plugin");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int status = response.getStatusLine().getStatusCode();
            if (status != 200) {
                throw new IOException("GitHub API returned HTTP " + status
                        + ". Set a specific version in the tool configuration to avoid this call.");
            }
            String json = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            String tagName = extractJsonStringField(json, "tag_name");
            String ver = stripLeadingV(tagName);
            log.getLogger().println("[Harness CLI] Latest version resolved: " + ver);
            return ver;
        }
    }

    /**
     * Builds the GitHub release download URL.
     * Pattern: https://github.com/harness/harness-cli/releases/download/v{ver}/hc_{ver}_{os}_{arch}.tar.gz
     */
    private static String buildDownloadUrl(String version, String os, String arch) {
        String tag       = "v" + version;
        String assetName = "hc_" + version + "_" + os + "_" + arch + ".tar.gz";
        return Constants.GITHUB_RELEASES_BASE + "/download/" + tag + "/" + assetName;
    }

    /**
     * Downloads the tar.gz from GitHub (following redirects) and extracts it into {@code toolHome}.
     */
    private static void downloadAndExtract(CloseableHttpClient httpClient, String downloadUrl,
                                           FilePath toolHome, TaskListener log)
            throws IOException, InterruptedException {

        HttpGet request = new HttpGet(downloadUrl);
        request.setHeader("Accept", "application/octet-stream");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int status = response.getStatusLine().getStatusCode();
            if (status < 200 || status >= 300) {
                throw new IOException(
                        "Failed to download Harness CLI — HTTP " + status + " from: " + downloadUrl);
            }
            try (InputStream in = response.getEntity().getContent()) {
                toolHome.untarFrom(in, FilePath.TarCompression.GZIP);
            }
        }
        log.getLogger().println("[Harness CLI] Archive extracted into: " + toolHome.getRemote());
    }

    /**
     * Searches for the hc binary in {@code toolHome} after the tar.gz has been extracted.
     * Tries the root first, then immediate subdirectories (some releases nest inside a folder).
     */
    private static FilePath findBinaryAfterExtraction(FilePath toolHome, String binaryName,
                                                       TaskListener log)
            throws IOException, InterruptedException {

        // 1. Binary directly in toolHome
        FilePath direct = toolHome.child(binaryName);
        if (direct.exists() && direct.length() > MIN_BINARY_SIZE_BYTES) {
            return direct;
        }

        // 2. Binary nested one level deep (e.g. hc_1.3.30_linux_x86_64/hc)
        List<FilePath> children = toolHome.listDirectories();
        for (FilePath dir : children) {
            FilePath candidate = dir.child(binaryName);
            if (candidate.exists() && candidate.length() > MIN_BINARY_SIZE_BYTES) {
                log.getLogger().println("[Harness CLI] Binary found in " + dir.getName()
                        + "/ — moving to tool root.");
                candidate.copyTo(direct);
                return direct;
            }
        }

        throw new IOException("[Harness CLI] Could not find '" + binaryName
                + "' after extracting the archive. Contents of " + toolHome.getRemote() + ": "
                + toolHome.list());
    }

    // -------------------------------------------------------------------------

    /** Strips a leading 'v' from a version string. "v1.3.30" → "1.3.30", "1.3.30" → "1.3.30" */
    private static String stripLeadingV(String ver) {
        return ver != null && ver.startsWith("v") ? ver.substring(1) : ver;
    }

    /**
     * Minimal JSON field extractor — avoids pulling in a full JSON library for a single field.
     * Handles the simple string-value case returned by the GitHub Releases API.
     */
    private static String extractJsonStringField(String json, String fieldName) throws IOException {
        String needle = "\"" + fieldName + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) {
            throw new IOException("Field '" + fieldName + "' not found in GitHub API response");
        }
        int colon = json.indexOf(':', idx + needle.length());
        int open  = json.indexOf('"', colon + 1);
        int close = json.indexOf('"', open + 1);
        if (open < 0 || close < 0) {
            throw new IOException("Malformed JSON for field '" + fieldName + "'");
        }
        return json.substring(open + 1, close);
    }

    // -------------------------------------------------------------------------

    @Extension
    public static final class DescriptorImpl extends ToolInstallerDescriptor<HarnessCliGitHubInstaller> {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Install from GitHub releases (harness/harness-cli)";
        }

        @Override
        public boolean isApplicable(Class<? extends ToolInstallation> toolType) {
            return toolType == HarnessCliInstallation.class;
        }
    }
}
