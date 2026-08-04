package io.jenkins.plugins.har.cli.callables;

import hudson.FilePath;
import hudson.remoting.VirtualChannel;
import jenkins.MasterToSlaveFileCallable;

import java.io.File;
import java.io.IOException;

/**
 * Creates a temporary directory on the agent node to use as the Harness CLI home.
 *
 * <p>Placed as a sibling of the workspace so it does not pollute the build workspace:
 * {@code <workspace-parent>/<workspace-name><buildNumber>tmp/harness/}
 *
 * <p>For example, if the workspace is {@code /var/jenkins/workspace/my-job} and the build
 * number is {@code 42}, the temp dir will be:
 * {@code /var/jenkins/workspace/my-job42tmp/harness/}
 *
 * <p>This directory should be set as {@code HC_HOME} (or the equivalent Harness CLI env var)
 * so that {@code hc auth login} writes credentials there instead of {@code ~/.harness/},
 * preventing interference between concurrent builds sharing the same agent.
 * The directory is registered for deletion on JVM exit.
 *
 * Keep TempDirCreator unused for now — if the Harness CLI adds env var support in a future version , you just wire it back in
 */
public class TempDirCreator extends MasterToSlaveFileCallable<FilePath> {

    private static final long serialVersionUID = 1L;

    private final String buildNumber;
    private final FilePath workspace;

    public TempDirCreator(String buildNumber, FilePath workspace) {
        this.buildNumber = buildNumber;
        this.workspace = workspace;
    }

    @Override
    public FilePath invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
        FilePath tempDir = workspace.sibling(workspace.getName() + buildNumber + "tmp");
        if (tempDir == null) {
            throw new IOException(
                    "Failed to create Harness CLI temporary directory — workspace has no parent directory");
        }
        tempDir = tempDir.child("harness");
        File tempDirFile = new File(tempDir.getRemote());
        if (tempDirFile.mkdirs()) {
            tempDirFile.deleteOnExit();
        }
        return tempDir;
    }
}

