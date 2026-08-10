package io.jenkins.plugins.har;

public final class Constants {

    public static final String HARNESS_UPLOAD_STEP = "harUpload";
    public static final String DEFAULT_BASE_URL = "https://app.harness.io";
    public static final String PLUGIN_SHORT_NAME = "harness-plugin";
    public static final String DISPLAY_NAME = "Harness Plugin";
    public static final int DEFAULT_TIMEOUT_MS = 30000;
    public static final int DEFAULT_RETRY_COUNT = 0;

    //harness cli urls
    public static final String GITHUB_RELEASES_BASE = "https://github.com/harness/harness-cli/releases";
    public static final String GITHUB_API_LATEST = "https://api.github.com/repos/harness/harness-cli/releases/latest";

    private Constants() {
    }
}
