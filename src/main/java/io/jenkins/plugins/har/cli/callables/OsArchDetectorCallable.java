package io.jenkins.plugins.har.cli.callables;

import io.jenkins.plugins.har.cli.HarnessOsUtils;
import jenkins.security.MasterToSlaveCallable;
import java.io.IOException;
import java.util.regex.Pattern;

public class OsArchDetectorCallable extends MasterToSlaveCallable<String, IOException> {
    private static final long serialVersionUID = 1L;
    private static final Pattern OS_ARCH_SEPARATOR = Pattern.compile("\\|");
    private static final String DEFAULT_ARCH = "x86_64";

    @Override
    public String call() throws IOException {
        return HarnessOsUtils.getOs() + "|" + HarnessOsUtils.getArch();
    }

    public static String[] parseOsInfo(String osInfo) {
        String[] parts = OS_ARCH_SEPARATOR.split(osInfo, 2);
        String os = parts[0];
        String arch = parts.length > 1 ? parts[1] : DEFAULT_ARCH;
        return new String[]{os, arch};
    }
}
