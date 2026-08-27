package io.jenkins.plugins.har.cli;

import java.util.Locale;

public class HarnessOsUtils {

    private HarnessOsUtils() {}

    /**
     * Returns the OS segment used in Harness CLI GitHub release asset names.
     * Matches the actual naming from harness/harness-cli releases:
     *   linux | mac-os | windows
     *
     * Designed to run on the agent node (inside a MasterToSlaveCallable).
     */
    public static String getOs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "mac-os";
        return "linux";
    }

    /**
     * Returns the architecture segment used in Harness CLI GitHub release asset names.
     * Matches the actual naming from harness/harness-cli releases:
     *   x86_64 | arm64 | i386
     *
     * Designed to run on the agent node (inside a MasterToSlaveCallable).
     */
    public static String getArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.equals("aarch64") || arch.equals("arm64")) return "arm64";
        if (arch.startsWith("arm")) return "arm64";  // treat arm variants as arm64
        if (arch.equals("i386") || arch.equals("i486") || arch.equals("i586") || arch.equals("i686")) return "i386";
        return "x86_64"; // amd64 / x86_64
    }

    public static String getBinaryName(boolean isWindows) {
        return isWindows ? "hc.exe" : "hc";
    }
}
