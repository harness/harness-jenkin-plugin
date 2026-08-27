# Harness Jenkins Plugin

A Jenkins plugin that integrates the [Harness CLI (`hc`)](https://github.com/harness/harness-cli) into Jenkins pipelines and Freestyle jobs, enabling you to push artifacts to the Harness Artifact Registry directly from your CI builds.

---

## Installing and configuring the plugin

1. Install the Harness Plugin by going to
   **Manage Jenkins | Manage Plugins**.<br><img src="docs/install-plugin.png" width="70%">
2. Configure your Harness CLI details by going to
   **Manage Jenkins | Configure System**.<br><img src="docs/configure-plugin.png" width="30%">
3. Configure Harness CLI as a tool in Jenkins as described in
   the [Configuring Harness CLI as a tool](#configuring-harness-cli-as-a-tool) section.

---

## Configuring Harness CLI as a tool

To use Harness CLI in your pipeline jobs, configure it as a tool in Jenkins by going to **Manage Jenkins |
Global Tool Configuration**.

### Automatic installation from GitHub

If your agent has access to the internet, you can set the installer to automatically download Harness CLI
from [GitHub releases (harness/harness-cli)](https://github.com/harness/harness-cli/releases) as shown in the below screenshot.

<img src="docs/automatic-installation.png" width="30%">

---

## Requirements

| Requirement | Details |
|---|---|
| Harness account | Any tier |
| Harness Personal Access Token (PAT) | Format: `pat.<AccountID>.<random>.<random>` |

---


## Usage

### Step 1 — Configure the Harness CLI tool installation

Go to **Manage Jenkins → Tools** and scroll to **Harness CLI (hc) installations**.

Click **Add Harness CLI (hc)**, give it a name (e.g. `harness-cli`), enable **Install automatically**, and set the desired version. The plugin downloads the binary from the [harness/harness-cli](https://github.com/harness/harness-cli/releases) GitHub releases automatically.

![Harness CLI tool installation](docs/screenshot-tools-installation.png)

> Leave the version field empty to always install the latest release.

---

### Step 2 — Configure Harness credentials

Go to **Manage Jenkins → System** and scroll to **Harness CLI Configuration**.

| Field | Description |
|---|---|
| **API URL** | Harness API endpoint, e.g. `https://app.harness.io` |
| **API Token** | Personal Access Token (PAT) — stored as a Jenkins secret |
| **Organization ID** | Optional. Your Harness org slug, e.g. `default` |
| **Project ID** | Optional. Your Harness project slug |

![Harness CLI system configuration](docs/screenshot-system-config.png)

The plugin automatically runs `hc auth login` before the first `hc` step in every build. The API token is always masked in build logs.

---

### Step 3 — Use in a Freestyle job

In a Freestyle job configuration, go to **Build Steps → Add build step** and select **Run Harness CLI (hc) command**.

![Add build step dropdown](docs/screenshot-add-build-step.png)

Select the CLI installation (or leave as **Use hc from system PATH**) and type the `hc` command to run. You may include or omit the leading `hc`, e.g.:

```
artifact push rpm my-repo /path/to/file.rpm
```

![Run Harness CLI command build step](docs/screenshot-build-step-config.png)

---

## Pipeline Example

### Declarative Pipeline — push an artifact

```groovy
pipeline {
    agent any

    tools {
        harnessCli 'harness-cli'   // matches the name set in Manage Jenkins → Tools
    }

    stages {
        stage('Build') {
            steps {
                sh 'mvn clean package -DskipTests'
            }
        }

        stage('Push to Harness Artifact Registry') {
            steps {
                hc 'artifact push generic my-registry target/myapp-1.0.jar'
            }
        }
    }
}
```

### Declarative Pipeline — multiple artifact types

```groovy
pipeline {
    agent any

    tools { harnessCli 'harness-cli' }

    stages {
        stage('Push RPM') {
            steps {
                hc 'artifact push rpm rpm-repo dist/mypackage-1.0.x86_64.rpm'
            }
        }

        stage('Push Debian image') {
            steps {
                hc 'artifact push Debian Debian-repo myimage'
            }
        }
    }
}
```

### Scripted Pipeline

```groovy
node {
    tool name: 'harness-cli', type: 'io.jenkins.plugins.har.cli.HarnessCliInstallation'

    stage('Push') {
        hc 'artifact push generic my-registry build/output.zip'
    }
}
```

### Available `hc` commands (examples)

| Command | Description |
|---|---|
| `hc version` | Print installed CLI version |
| `hc auth status` | Check current login status |
| `hc artifact push generic <registry> <file>` | Push a generic artifact |
| `hc artifact push rpm <registry> <file.rpm>` | Push an RPM package |
| `hc artifact push Debian <registry> <image>` | Push a Debian image |

> For the full command reference, see the [Harness CLI documentation](https://developer.harness.io/docs/platform/automation/cli/reference/).

---

## Contributing

Refer to [CONTRIBUTING.md](https://github.com/harness/harness/blob/main/CONTRIBUTING.md)

## License

Apache License 2.0, see [LICENSE](https://github.com/harness/harness/blob/main/LICENSE).