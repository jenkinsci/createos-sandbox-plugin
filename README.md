# CreateOS Sandbox

Provision ephemeral [CreateOS](https://createos.sh) Sandbox microVMs as Jenkins build
agents. Each build gets a fresh VM that is destroyed when the build finishes.

## Installation

Install **CreateOS Sandbox** from **Manage Jenkins > Plugins > Available plugins**, or with
the plugin installation manager:

```bash
jenkins-plugin-cli --plugins createos-sandbox
```

Releases are published automatically through the Jenkins project's continuous delivery
pipeline, so versions look like `3.v1a2b3c4d5e6f` rather than `1.2.3`.

## Requirements

- Jenkins 2.541.3 or newer
- A CreateOS account and API key
- An agent root filesystem containing a JVM — see [Agent Template](#agent-template).
  The stock images do not have one.

## Configuration

1. **Manage Jenkins > Credentials** — add a "Secret text" credential holding your CreateOS
   API key.
2. **Manage Jenkins > Clouds > New cloud** — select "CreateOS Sandbox".
3. Configure the cloud:
   - **API URL**: `https://api.sb.createos.sh` (HTTPS is required except for explicit loopback
     addresses used by local test servers)
   - **Credentials**: the API key credential from step 1
   - **Container Cap**: maximum concurrent sandboxes (default 10)
4. Add a **Sandbox Template**:
   - **Label**: `createos`, or any label your jobs will request
   - **Shape**: for example `s-1vcpu-1gb`
   - **Root Filesystem**: the template you built per [Agent Template](#agent-template).
     A stock image such as `devbox:1` has no JVM and the agent will not start.
   - **Launch Method**: `Inbound WebSocket` (default), or `SSH over CreateOS tunnel` for
     controllers where inbound agent connections are blocked — see
     [Launch Methods](#launch-methods).
   - **Allow Pipeline-defined Overrides**: disable for templates that Jenkinsfiles may
     select by label but must not override through `agent { createos inheritFrom: ... }`
   - **Region**: optional CreateOS region
   - **Root Disk Size (MiB)**: optional root disk override; `0` uses the shape default
   - **Private Networks**: optional CreateOS network ids or names, comma- or
     newline-separated
   - **Disks**: optional S3 disk mounts, each with `id`, `mountPath`, and optional `subPath`
   - **Reuse agent between builds**: off by default, so each agent takes one build and is
     then deleted. When enabled, an agent stays up for further builds, and its sandbox keeps
     billing while idle.
   - **Idle Timeout (minutes)**: how long an idle agent is kept before it is deleted with
     its sandbox (default 1). With agent reuse enabled, `0` keeps agents forever; delete
     them by hand when they are no longer needed.
   - **Delete agents on controller restart**: off by default, so a restart reconnects
     agents and their builds resume. Enable it for agents that must never outlive the
     controller process; a restart then deletes them and running builds fail.

Network and disk references are validated before the sandbox is created. A missing network
or disk fails the launch with a clear message in the Jenkins log instead of producing a
misconfigured sandbox.

Set **Manage Jenkins > System > Jenkins URL** to a URL the sandbox can reach. A sandbox
cannot reach the controller's `localhost`. This is required for **Inbound WebSocket**. It is
not required for **SSH over CreateOS tunnel**, where Jenkins connects outward to the sandbox
through the CreateOS tunnel API.

> **Give the controller zero executors.** If the built-in node runs builds, a build can land
> in the controller's JVM, where `JENKINS_HOME` and the stored API credential live.
>
> Exec-mode pipelines should therefore name the agent label (`agent { label 'createos' }`)
> rather than `agent any`: `createosSh` works over the API, but `writeFile`/`readFile` and
> `createosUpload`/`createosDownload` need a workspace, so a node is still required. An
> unlabelled `agent any` job is not served at all — `CreateOSSlave` is `EXCLUSIVE`, so it
> accepts only jobs naming its label, and the cloud declines a null label rather than
> provisioning a sandbox nothing can use.

### SSH credentials

SSH launch mode needs one thing: a Jenkins SSH private-key credential, selected by the
template's **SSH Credentials** field.

The private key, optional passphrase, and username stay in Jenkins Credentials. The public
key is derived from that credential and written to the user's `authorized_keys` before
`sshd` starts, so there is no second field to keep in sync and no way to install a public
key that does not match the private one the launcher authenticates with. The credential
username must exist in the agent root filesystem; the template in `Dockerfile.agent`
provides `jenkins` and `/home/jenkins`.

For a passphrase-protected private key, store the passphrase in the Jenkins SSH credential.
The sandbox never receives the passphrase. Both PEM and OpenSSH key containers work,
encrypted or not, because the key is read with the same library that authenticates the
launch.

## Pipeline Usage

Label-based templates work as with any Jenkins cloud:

```groovy
pipeline {
    agent { label 'createos' }
    stages {
        stage('Build') {
            steps {
                echo 'Hello from CreateOS Sandbox'
                sh 'uname -a'
            }
        }
    }
}
```

For per-pipeline CreateOS settings, use the Declarative agent:

```groovy
pipeline {
    agent {
        createos inheritFrom: 'createos',
                 region: 'us',
                 diskMiB: 20480,
                 networks: ['my-network'],
                 disks: [[id: 'my-maven-cache',
                          mountPath: '/mnt/cache/maven',
                          subPath: 'jenkins/my-job']]
    }

    stages {
        stage('Build') {
            steps {
                sh 'mvn -B -Dmaven.repo.local=/mnt/cache/maven clean package'
            }
        }
    }
}
```

`inheritFrom` names a configured sandbox template label and uses it as the base. Fields in
the Jenkinsfile override the inherited template for that Pipeline run only. The plugin
generates a unique temporary label internally, so concurrent builds with different disks or
networks cannot take each other's sandbox.

Both Declarative `agent { createos ... }` and exec-mode `createosSandbox(...)` require
`inheritFrom`; a Jenkinsfile cannot build a fully ad-hoc template from shape and rootfs alone.
This keeps root filesystems, launch mode, credentials, networks, disks, and other privileged
defaults anchored to administrator-defined templates.

Administrators control overrides per template with **Allow Pipeline-defined Overrides**.
When disabled, Jenkinsfiles may still select that template by label, but may not inherit
from it and override shape, rootfs, remoteFs, networks, disks, disk size, or region. On a
controller with untrusted Jenkinsfiles, disable it on every privileged template.

Each queued build gets its own one-executor sandbox, up to the container cap. The agent
stops accepting new tasks as soon as it accepts its first build, and is destroyed when that
build finishes.

Template-level network and disk configuration applies to every build using that label — for
example a `createos-cache` template that attaches a private network and mounts a shared
Maven cache, which pipelines select with `agent { label 'createos-cache' }`.

## Exec Mode

For short jobs, the exec-mode steps skip Jenkins node registration, JNLP, Remoting, and
Durable Task. The Pipeline still needs a node for its workspace — name the CreateOS label,
not `agent any` — but build commands run inside CreateOS through the exec API.

`createosSandbox` must specify `inheritFrom`, naming a template configured by an administrator.
Fields supplied by the Pipeline are applied only when that template allows Pipeline-defined
overrides.

```groovy
pipeline {
    agent { label 'createos' }

    stages {
        stage('Build in CreateOS') {
            steps {
                createosSandbox(
                    inheritFrom: 'createos',
                    shape: 's-1vcpu-1gb',
                    rootfs: 'jenkins-agent-jdk25',
                    region: 'eu',
                    diskMiB: 20480,
                    networks: ['my-network'],
                    disks: [[
                        id: 'my-cache-disk',
                        mountPath: '/mnt/cache',
                        subPath: 'my-job'
                    ]]
                ) {
                    createosUpload(
                        source: '.',
                        target: '/workspace',
                        excludes: ['.git/**', 'node_modules/**', 'dist/**', 'target/**']
                    )

                    createosSh '''
                        set -eux
                        cd /workspace
                        echo "agent=$(hostname)"
                        df -h /
                        mount | grep ' /mnt/cache '
                        ./build.sh
                    '''

                    createosDownload source: '/workspace/build/result.txt',
                                     target: 'result.txt'
                }
            }
        }
    }
}
```

Step behaviour:

- `createosSandbox` creates the sandbox, waits for `running`, runs the block, and destroys
  the sandbox on success, failure, or abort.
- `createosSh` streams stdout/stderr into the Jenkins log and fails the step on a non-zero
  exit unless `returnStatus: true` is set.
- `createosUpload` recursively uploads workspace files, or a single file, to an absolute
  sandbox path.
- `createosDownload` downloads one sandbox file into the Jenkins workspace. Directory
  download is not implemented yet.

Use agent mode when you need full Jenkins workspace semantics: `checkout scm`,
`archiveArtifacts`, or arbitrary `sh` steps inside the sandbox. Use exec mode when startup
latency matters and you can explicitly upload inputs and download outputs.

## Launch Methods

Templates default to **Inbound WebSocket**: Jenkins creates the sandbox, starts `agent.jar`
through the CreateOS exec API, and the agent connects back to the controller over
HTTPS/WebSocket.

For controllers that cannot accept inbound agent connections, choose **SSH over CreateOS
tunnel**. The launcher creates the sandbox, injects the credential's public key, starts
`sshd`, opens a local loopback proxy on the controller, and connects Jenkins' SSH launcher
to sandbox port 22 through the CreateOS tunnel API. The build still uses Jenkins Remoting,
so Pipeline `sh`, logs, workspace operations, and artifact handling behave exactly as with a
normal SSH-launched agent.

| Method | Use when | Network direction |
|---|---|---|
| Inbound WebSocket | The sandbox can reach the Jenkins URL over HTTPS and WebSockets are allowed. | Agent connects to controller. |
| SSH over CreateOS tunnel | The controller must initiate the connection — for example where inbound JNLP is blocked and WebSockets are disabled. | Controller connects to sandbox SSH through the CreateOS tunnel API. |

SSH launch sequence:

```text
Jenkins controller
  → POST /v1/sandboxes
  → poll until sandbox is running
  → exec: install the credential's public key into /home/jenkins/.ssh/authorized_keys
  → exec: start sshd
  → POST /v1/sandboxes/{id}/tunnel/22
  → open local loopback port on the controller
  → Jenkins SSHLauncher connects to localhost:<local-port>
  → tunnel forwards bytes to sandbox:22
  → sshd authenticates the Jenkins private key
  → SSHLauncher runs: java -jar /opt/jenkins/agent.jar
  → Jenkins Remoting carries Pipeline steps, stdout/stderr, and workspace traffic
```

When the build finishes, aborts, or the node goes offline, the plugin closes the local
tunnel proxy and deletes the sandbox. A mid-build SSH or Remoting disconnect is treated as
disposable agent loss; use attached disks for state that must survive agent deletion.

## Dependency Caches

CreateOS disks work well as persistent cache storage across otherwise disposable agents.
Use them for stable cache artifacts, not for high-churn package-manager temp directories.

For Maven-style caches, point the tool directly at the mounted disk:

```groovy
pipeline {
    agent {
        createos inheritFrom: 'createos',
                 disks: [[id: 'my-maven-cache', mountPath: '/mnt/cache/maven']]
    }

    stages {
        stage('Build') {
            steps {
                sh 'mvn -B -Dmaven.repo.local=/mnt/cache/maven clean package'
            }
        }
    }
}
```

For Node projects, prefer a tarball cache. Install and build in the normal Jenkins
workspace, then store a compressed `node_modules` on the mounted disk:

```groovy
pipeline {
    agent {
        createos inheritFrom: 'createos',
                 disks: [[id: 'my-npm-cache', mountPath: '/mnt/cache']]
    }

    stages {
        stage('Build') {
            steps {
                sh '''
                    set -eux
                    mkdir -p /mnt/cache/react-app "$WORKSPACE/.npm-cache"

                    if [ -f /mnt/cache/react-app/node_modules.tgz ]; then
                        tar -xzf /mnt/cache/react-app/node_modules.tgz
                    fi

                    npm install --cache "$WORKSPACE/.npm-cache" --prefer-offline --no-audit --no-fund
                    npm test
                    npm run build

                    tar -czf /mnt/cache/react-app/node_modules.tgz node_modules package-lock.json
                '''
            }
        }
    }
}
```

Do not put npm's `_cacache`, or a large live `node_modules` install, directly on a mounted
disk. The mount is S3-backed through FUSE, and npm/esbuild's create-delete-postinstall churn
can fail with `ENOTCONN` against it. The reliable split is:

- local workspace: `npm install`, `npm test`, `npm run build`, npm's internal cache, temp
  files and build output
- mounted disk: cache tarballs such as `node_modules.tgz`

Group related commands into one `sh` step. Every Pipeline `sh` is a separate Durable Task:

```groovy
node('createos') {
    sh '''
        git clone https://github.com/example/project.git
        cd project
        ./build.sh
    '''
}
```

For a Jenkins-managed repository, prefer `checkout scm`:

```groovy
node('createos') {
    checkout scm
    sh './build.sh'
}
```

## Agent Template

**The stock rootfs images do not work.** `devbox:1`, `debian:13`, `ubuntu:26.04` and
`alpine:3.20` ship no JVM, so `agent.jar` downloads and then fails to start. Build an agent
rootfs from [`Dockerfile.agent`](Dockerfile.agent) and point the Sandbox Template's **Root
Filesystem** at it:

```bash
createos template submit --name jenkins-agent-jdk25 --dockerfile Dockerfile.agent
createos template list   # wait for status: ready
```

`Dockerfile.agent` is the single source of truth for that image. The cloud configuration
references the template by *name*, so a rebuild never requires touching Jenkins. Naming
templates per JDK — `jenkins-agent-jdk25`, `jenkins-agent-jdk21` — lets several coexist so a
job can switch rootfs without a rebuild. Rebuilding one name deletes and resubmits it (the
API rejects duplicates), so launches using that name fail during the window; rebuild when
the queue is quiet.

What the image contains and why is documented in the Dockerfile. In short: a checksum-pinned
Temurin JDK, `curl`, `ca-certificates`, `git`, `fontconfig`, `fuse3` + `geesefs` for disk
mounts, `nodejs`/`npm`, and `/home/jenkins`.

### Version compatibility

Jenkins' [Java support policy](https://www.jenkins.io/doc/book/platform-information/support-policy-java/)
covers "all components of the Jenkins system, including the Jenkins controller, **all types
of agents**, CLI clients" — so the JVM in this template is governed by the same table as the
controller's:

| Controller line | Java the agent must run |
|---|---|
| LTS 2.555.1 (Apr 2026) / weekly 2.545 and later | **21 or 25** — Java 17 is no longer supported |
| LTS 2.541.1 / weekly 2.534 | 17, 21, or 25 |

| Piece | Constraint |
|---|---|
| `agent.jar` | **Baked into the image**, pinned and checksummed (`REMOTING_URL` / `REMOTING_SHA256` in `Dockerfile.agent`). A controller accepts any remoting at or above its published minimum, so one pinned jar serves every supported controller. Falls back to `$JENKINS_URL/jnlpJars/agent.jar` on a rootfs built before this. |
| JVM in the template | **Temurin 25 LTS** — the newest version in the table above. Drop to 21 (also supported) if a build toolchain is not yet 25-clean. |
| Transport | Inbound WebSocket or SSH over CreateOS tunnel. WebSocket needs no `slaveAgentPort`; SSH needs `sshd` and a Jenkins SSH credential. |
| Jenkins URL | Required for WebSocket, and must be reachable from the sandbox. `localhost` on the controller is not routable from inside a sandbox microVM, and is the most common cause of a WebSocket launch timeout. |

The JDK and remoting pins live in `Dockerfile.agent` as `ARG` values with matching SHA-256
checksums. The image build fails closed on a mismatch.

## Architecture

```
Pipeline enters queue for a configured label
  → CreateOSQueueListener requests an immediate provisioning review
  → CreateOSCloud.provision() uses Jenkins' excess workload and the container cap
  → one CreateOSSlave is planned per requested executor (up to containerCap)
  → completed PlannedNode future wakes NodeProvisioner
  → CreateOSLauncher.launch():
      1. POST /v1/sandboxes
      2. Poll until "running"
      3. Start agent.jar inside the sandbox
      4. Agent connects back over WebSocket
      5. Wait for Jenkins to mark the agent online
  → Job runs inside sandbox
  → OnceRetentionStrategy triggers _terminate()
      → DELETE /v1/sandboxes/{id}
```

For SSH templates, steps 3 and 4 are replaced by starting `sshd`, opening a CreateOS tunnel
to port 22, and launching Jenkins Remoting through Jenkins' SSH launcher.

On Jenkins startup, stale CreateOS nodes are terminated through the normal agent lifecycle,
so their sandboxes are destroyed too.

## Why Is It Slow?

Jenkins Pipeline `sh` uses the Durable Task plugin. It writes a temporary script, log,
heartbeat, and result state in the agent workspace and monitors them over Jenkins Remoting,
so a controller can reconnect to a long-lived agent and resume monitoring after a restart.

That differs from a GitHub Actions runner, which launches and streams a `run` command
directly from its local runner process. Jenkins pays extra for node registration, Remoting,
agent listeners, and durable command monitoring.

Durable Task does not archive build outputs. Use `archiveArtifacts` or `stash` when output
must survive sandbox deletion:

```groovy
node('createos') {
    sh './build.sh'
    archiveArtifacts artifacts: 'build/**'
}
```

This is what lets a build survive a controller restart. The agent's sandbox keeps running
while the controller is down, the agent reconnects once it is back, and a running `sh` resumes
where it left off, for both inbound and SSH agents. Templates with **Delete agents on controller
restart** enabled are the exception: their agents are deleted on startup, and running `sh` steps
cannot resume.

### Launch Method Timing

On a CreateOS-hosted Jenkins controller running 2.568.2 with the `jenkins-agent-jdk25`
rootfs and `s-2vcpu-2gb` agents, a trivial Pipeline smoke test produced:

| Method | Runs | Average build duration | Min | Max |
|---|---:|---:|---:|---:|
| SSH over CreateOS tunnel | 10/10 success | 6.40s | 5.94s | 7.03s |
| Inbound WebSocket | 10/10 success | 38.03s | 35.50s | 49.07s |

This benchmark is intentionally narrow: it measures provision + agent connection + a tiny
shell step, not a real application build. SSH was faster here because the controller
initiates a direct SSH launcher connection through the tunnel, instead of waiting for the
sandbox agent to connect back over WebSocket.

### Exec-Mode Tradeoff

Exec mode bypasses JNLP and proxies commands through the CreateOS exec API. It avoids node
registration, JNLP, Remoting, and Durable Task, trading Jenkins restart recovery and standard
workspace semantics for lower latency, and requiring explicit upload and download for files.

## Known Limitations

1. **Agent initialization remains expensive.** Installed Jenkins `ComputerListener`
   extensions and Remoting initialization can delay task acceptance after the sandbox and
   WebSocket are ready.
2. **Pipeline `sh` has fixed overhead.** Each `sh` is a Durable Task. Combine related
   commands into one shell block for short jobs.
3. **WebSocket requires a reachable Jenkins URL.** `localhost` on the controller is not
   reachable from the sandbox.
4. **SSH recovery is limited by the disposable lifecycle.** Jenkins may reconnect a dropped
   SSH agent connection, but once the node is terminated the plugin closes the tunnel and
   deletes the sandbox.
5. **Sandboxes keep running, and billing, while the controller is down.** That is what lets
   agents reconnect and builds resume after a restart. Templates with **Delete agents on
   controller restart** enabled are deleted on startup instead. A periodic sweep destroys
   sandboxes this controller named but no longer has an agent for, so a node lost while the
   controller was down cannot leave a sandbox billing indefinitely. The sweep runs in the
   controller, though: a controller that is deleted outright while its agents are running
   leaves their sandboxes behind, to be removed from CreateOS by hand.

## Building from source

```bash
mvn clean verify          # compile, test, Spotless, Checkstyle
mvn hpi:run               # run a Jenkins with the plugin loaded, on :8080
```

Requires JDK 21 or 25 and Maven 3.9+. Java sources follow the
[Google Java Style Guide](https://google.github.io/styleguide/javaguide.html); `mvn spotless:apply`
formats them. Spotless and Checkstyle are bound to the `validate` phase, so any normal build
runs them.

## License

MIT — see [LICENSE](LICENSE).
