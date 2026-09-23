package sh.createos.jenkins.sandbox;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.fasterxml.jackson.databind.JsonNode;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.plugins.sshslaves.verifiers.NonVerifyingKeyVerificationStrategy;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.SlaveComputer;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Creates a CreateOS sandbox and launches a Jenkins agent inside it. */
public class CreateOSLauncher extends JNLPLauncher {

  private static final Logger LOGGER = Logger.getLogger(CreateOSLauncher.class.getName());

  /** Where Dockerfile.agent bakes the pinned remoting jar. */
  static final String BAKED_AGENT_JAR = "/opt/jenkins/agent.jar";

  /**
   * Wraps a value for safe use inside {@code bash -c}.
   *
   * <p>Every dynamic value here reaches a shell, and several are set by whoever writes the
   * Jenkinsfile rather than by a Jenkins administrator: {@code remoteFs} and {@code rootfs} come
   * from {@link SandboxTemplate}, and {@link CreateOSPipelineSupport} lets an {@code agent {
   * createos { ... } }} block override the administrator's values. Unquoted, {@code remoteFs} of
   * {@code /home/jenkins; touch /tmp/pwned; #} changes the command's structure, and an apostrophe
   * in {@code rootfs} escapes the message it sits in. A path containing a space breaks the command
   * without any malice at all.
   *
   * <p>Single quotes rather than escaping: inside them the shell expands nothing, so the only
   * character needing care is the single quote itself, closed and reopened around an escaped one.
   */
  static String shellQuote(String value) {
    if (value == null) {
      return "''";
    }
    return "'" + value.replace("'", "'\\''") + "'";
  }

  /**
   * Builds the shell that puts agent.jar in place, and checks the rootfs can run it.
   *
   * <p>Extracted so the test executes this exact string rather than a copy of it. A test that
   * restates the script only proves the copy works, and silently stops covering the launcher the
   * moment either side is edited — the two drift apart with nothing failing.
   *
   * <p>{@code bakedJar} is a parameter rather than a constant read for the same reason: it lets the
   * test point both branches at a temporary directory and run them for real, instead of asserting
   * on the text of a command nobody executed.
   */
  static String prepareCommand(String bakedJar, String remoteFsRaw, String rootfs, String jarUrl) {
    String remoteFs = shellQuote(remoteFsRaw);
    return "set -e; mkdir -p "
        + remoteFs
        + "; command -v java >/dev/null || { echo 'java not found in rootfs' "
        + shellQuote(rootfs)
        + " '- build one with Dockerfile.agent' >&2; exit 127; };"
        // -s and the ZIP magic, not -r: an empty or truncated file at this path would
        // otherwise be copied over and fail much later, during the JNLP handshake, where
        // the cause is no longer visible. A well-formed jar of the wrong VERSION still
        // gets through here and is rejected at connect — the loud failure the pin is for.
        + " if [ -s "
        + bakedJar
        + " ] && [ \"$(head -c2 "
        + bakedJar
        + ")\" = PK ]; then cp "
        + bakedJar
        + " "
        + remoteFs
        + "/agent.jar; echo 'agent.jar: baked into rootfs';"
        + " else if [ -e "
        + bakedJar
        + " ]; then"
        + " echo 'agent.jar: baked copy unusable, falling back to controller' >&2; fi;"
        + " echo 'agent.jar: downloading from controller'; curl -fsSL "
        + shellQuote(jarUrl)
        + " -o "
        + remoteFs
        + "/agent.jar; fi";
  }

  /** Creates a launcher that connects the inbound agent over WebSocket. */
  public CreateOSLauncher() {
    super(true); // WebSocket mode
  }

  // Descriptor override like K8s plugin — not registered via @Extension
  @Override
  public Descriptor<ComputerLauncher> getDescriptor() {
    return new DescriptorImpl();
  }

  private static class DescriptorImpl extends Descriptor<ComputerLauncher> {}

  /**
   * Reports a non-zero exec as a build error and returns false. The exec API returns HTTP 200 for a
   * command that ran and failed, so the exit code is the only signal that it did.
   */
  private static boolean checkExec(JsonNode response, String what, TaskListener listener) {
    JsonNode result = response.path("result");
    int exitCode = result.path("exit_code").asInt(-1);
    if (exitCode == 0) {
      return true;
    }
    listener.error(what + " failed (exit " + exitCode + "): " + result.path("stderr").asText());
    return false;
  }

  @Override
  public void launch(SlaveComputer computer, TaskListener listener) {
    if (!(computer instanceof CreateOSComputer createOSComputer)) {
      listener.error("CreateOS launcher received unexpected computer type: " + computer);
      return;
    }

    // The sandbox this node owned before this attempt. A failed launch may only destroy a sandbox
    // it created itself: this one can be a running build's workspace, kept across a restart.
    String owned = null;
    try {
      final CreateOSSlave slave = createOSComputer.getNode();
      if (slave == null) {
        listener.error("Slave node is null");
        return;
      }
      owned = slave.getSandboxId();

      final CreateOSCloud cloud = slave.getCreateOSCloud();
      final SandboxTemplate template = slave.getTemplate();
      final CreateOSApiClient apiClient = cloud.buildApiClient();

      listener.getLogger().println("=== CreateOS Sandbox Agent Provisioning ===");
      listener.getLogger().println("Shape: " + template.getShape());
      listener.getLogger().println("RootFS: " + template.getRootfs());
      listener.getLogger().println("Label: " + template.getLabel());
      if (template.getRegion() != null && !template.getRegion().isBlank()) {
        listener.getLogger().println("Region: " + template.getRegion());
      }
      if (template.getDiskMiB() > 0) {
        listener.getLogger().println("Root disk: " + template.getDiskMiB() + " MiB");
      }
      if (!template.getNetworkIdList().isEmpty()) {
        listener.getLogger().println("Private networks: " + template.getNetworkIdList());
      }
      if (!template.getDisks().isEmpty()) {
        listener.getLogger().println("Disk attachments: " + template.getDisks().size());
      }

      /*
       * Step 1: Create the sandbox, or adopt the one this agent already owns.
       *
       * An agent whose controller restarted still has its sandbox: the node records the id, the
       * sandbox kept running, and its workspace survived. Creating a second sandbox here would
       * strand the first one and throw away the workspace a build may still be resuming into.
       *
       * An inbound agent needs nothing at all: its agent process is still running in that
       * sandbox and reconnects its WebSocket by itself once the controller is back. Starting a
       * second agent.jar would only race it for the same node. An SSH agent lost its tunnel with
       * the controller process, so it is relaunched through a new one below.
       */
      String sandboxId;
      if (adoptsExistingSandbox(slave, apiClient)) {
        sandboxId = slave.getSandboxId();
        if (template.sshLauncher() == null) {
          listener
              .getLogger()
              .println("Sandbox " + sandboxId + " is still running; waiting for its agent.");
          return;
        }
        listener.getLogger().println("Reconnecting to existing sandbox: " + sandboxId);
      } else {
        CreateOSSandboxRequest request =
            CreateOSSandboxRequest.fromTemplate(
                template, CreateOSSlave.sandboxName(slave.getNodeName()));
        listener.getLogger().println("Validating CreateOS disks and networks...");
        listener.getLogger().println("Creating sandbox...");
        sandboxId = apiClient.createSandbox(request);
        slave.setSandboxId(sandboxId);
        listener.getLogger().println("Sandbox created: " + sandboxId);

        // Step 2: Wait for running
        listener.getLogger().println("Waiting for sandbox to be ready...");
        apiClient.waitForRunning(sandboxId, Duration.ofMinutes(5));
        listener.getLogger().println("Sandbox is running.");
      }

      SshLaunchMethod sshLaunch = template.sshLauncher();
      if (sshLaunch != null) {
        launchSsh(computer, listener, slave, template, apiClient, sandboxId, sshLaunch);
      } else {
        launchInbound(createOSComputer, listener, slave, template, apiClient, sandboxId);
      }

    } catch (Exception e) {
      listener.error("Failed to launch agent: " + e.getMessage());
      LOGGER.log(Level.SEVERE, "Failed to launch CreateOS agent", e);

      try {
        CreateOSSlave slave = createOSComputer.getNode();
        if (slave != null && slave.getSandboxId() != null && !slave.getSandboxId().equals(owned)) {
          slave.terminateBackingSandbox(listener);
        }
      } catch (Exception cleanup) {
        LOGGER.log(Level.WARNING, "Cleanup failed", cleanup);
      }
    }
  }

  private void launchInbound(
      CreateOSComputer computer,
      TaskListener listener,
      CreateOSSlave slave,
      SandboxTemplate template,
      CreateOSApiClient apiClient,
      String sandboxId)
      throws Exception {
    // Download agent.jar (Java is pre-baked in template)
    String jenkinsUrl = jenkins.model.Jenkins.get().getRootUrl();
    if (jenkinsUrl == null) {
      listener.error("Jenkins URL is not configured.");
      slave.terminateBackingSandbox(listener);
      return;
    }
    listener.getLogger().println("Jenkins URL: " + jenkinsUrl);

    listener.getLogger().println("Preparing agent.jar...");
    /*
     * The java check belongs here rather than after launch: a rootfs without a JVM
     * otherwise fails inside the backgrounded nohup, where nothing reads the exit
     * code and the only symptom is a 120s timeout with no cause. See Dockerfile.agent.
     *
     * Prefer the jar baked into the rootfs. It is pinned and checksummed at build
     * time, so what runs is fixed by the image rather than by whatever the controller
     * serves at launch, and nothing is fetched on the agent's hot path. Jenkins accepts
     * any remoting at or above its published minimum, so one pinned jar serves every
     * supported controller — see the compatibility table in README.md.
     *
     * The curl stays as a fallback so a rootfs built before the jar was baked still
     * works. It is not dead code: `rootfs` is user-configurable and older templates
     * remain valid.
     */
    String remoteFs = shellQuote(template.getRemoteFs());
    JsonNode prepare =
        apiClient.runBash(
            sandboxId,
            prepareCommand(
                BAKED_AGENT_JAR,
                template.getRemoteFs(),
                template.getRootfs(),
                jenkinsUrl + "jnlpJars/agent.jar"));
    if (!checkExec(prepare, "Agent preparation", listener)) {
      slave.terminateBackingSandbox(listener);
      return;
    }

    String secret = computer.getJnlpMac();
    String agentName = slave.getNodeName();

    String agentCommand =
        "java -jar "
            + remoteFs
            + "/agent.jar"
            + " -url "
            + shellQuote(jenkinsUrl)
            + " -secret "
            + shellQuote(secret)
            + " -name "
            + shellQuote(agentName)
            + " -workDir "
            + remoteFs
            + " -webSocket";

    listener.getLogger().println("Starting JNLP agent: " + agentName);
    // stdin is redirected too: the exec API blocks until every inherited stream is
    // closed, so a background process holding stdin open hangs the call.
    JsonNode started =
        apiClient.runBash(
            sandboxId,
            "nohup " + agentCommand + " > " + remoteFs + "/agent.log 2>&1 < /dev/null &");
    if (!checkExec(started, "Agent start", listener)) {
      slave.terminateBackingSandbox(listener);
      return;
    }

    listener.getLogger().println("JNLP agent started, waiting for connection...");

    int timeout = 120; // seconds
    for (int i = 0; i < timeout; i++) {
      if (computer.isOnline()) {
        listener.getLogger().println("Agent connected successfully!");
        return;
      }
      if (i % 10 == 0 && i > 0) {
        listener.getLogger().println("  Waiting for agent to connect... (" + i + "s)");
      }
      Thread.sleep(1000);
    }

    listener.error("Timeout waiting for JNLP agent to connect after " + timeout + "s");
    // Last chance to say why: the sandbox is about to be destroyed with its logs.
    try {
      JsonNode tail = apiClient.runBash(sandboxId, "tail -50 " + remoteFs + "/agent.log");
      listener.getLogger().println("--- agent.log ---");
      listener.getLogger().println(tail.path("result").path("stdout").asText());
    } catch (Exception e) {
      listener.getLogger().println("Could not read agent.log: " + e.getMessage());
    }
    slave.terminateBackingSandbox(listener);
  }

  private void launchSsh(
      SlaveComputer computer,
      TaskListener listener,
      CreateOSSlave slave,
      SandboxTemplate template,
      CreateOSApiClient apiClient,
      String sandboxId,
      SshLaunchMethod sshLaunch)
      throws Exception {
    SSHUserPrivateKey credential = sshLaunch.resolveCredential();
    String username = credential.getUsername();
    if (username == null || username.isBlank()) {
      throw new IllegalStateException(
          "SSH credential requires a username: " + sshLaunch.getCredentialsId());
    }

    listener.getLogger().println("Preparing SSH access for user: " + username);
    JsonNode prepare =
        apiClient.runBash(
            sandboxId, sshPrepareCommand(template, username, sshLaunch.authorizedKey()));
    if (!checkExec(prepare, "SSH preparation", listener)) {
      slave.terminateBackingSandbox(listener);
      return;
    }

    CreateOSTunnelProxy proxy = new CreateOSTunnelProxy(apiClient, sandboxId, 22);
    proxy.start();
    slave.setTunnelProxy(proxy);
    listener
        .getLogger()
        .println("CreateOS SSH tunnel listening on 127.0.0.1:" + proxy.getLocalPort());

    SSHLauncher sshLauncher =
        new SSHLauncher(
            "127.0.0.1",
            proxy.getLocalPort(),
            sshLaunch.getCredentialsId(),
            null,
            "java",
            null,
            null,
            120,
            10,
            3,
            // The SSH server is a fresh disposable sandbox reached through a local loopback proxy.
            // The proxy opens a TLS-verified CreateOS tunnel to the API before forwarding bytes to
            // sandbox port 22, and ssh-keygen -A creates a new host key per sandbox. There is no
            // stable host identity to pin or trust-on-first-use value to persist, so Jenkins
            // authenticates the sandbox by the controller-owned private key instead.
            new NonVerifyingKeyVerificationStrategy());
    sshLauncher.launch(computer, listener);
  }

  static String sshPrepareCommand(SandboxTemplate template, String username, String authorizedKey) {
    return sshPrepareCommand(template, username, authorizedKey, "/run/sshd");
  }

  static String sshPrepareCommand(
      SandboxTemplate template, String username, String authorizedKey, String sshdRunDir) {
    String publicKey = shellQuote(authorizedKey.trim());
    String remoteFs = shellQuote(template.getRemoteFs());
    String runDir = shellQuote(sshdRunDir);
    String user = shellQuote(username);
    return "set -e; mkdir -p "
        + remoteFs
        + " "
        + runDir
        + ";"
        + " user="
        + user
        + ";"
        + " home=$(getent passwd \"$user\" 2>/dev/null | cut -d: -f6 || true);"
        + " if [ -z \"$home\" ] && [ \"$user\" = root ]; then home=/root; fi;"
        + " if [ -z \"$home\" ] && [ -d \"/home/$user\" ]; then home=\"/home/$user\"; fi;"
        + " if [ -z \"$home\" ]; then echo \"SSH user not found: $user\" >&2; exit 127; fi;"
        + " mkdir -p \"$home/.ssh\";"
        + " command -v java >/dev/null || { echo 'java not found in rootfs' >&2; exit 127; };"
        + " sshd_path=$(command -v sshd || true);"
        + " if [ -z \"$sshd_path\" ] && [ -x /usr/sbin/sshd ]; then sshd_path=/usr/sbin/sshd; fi;"
        + " if [ -z \"$sshd_path\" ]; then echo 'sshd not found in rootfs' >&2; exit 127; fi;"
        + " touch \"$home/.ssh/authorized_keys\";"
        + " grep -qxF "
        + publicKey
        + " \"$home/.ssh/authorized_keys\" || printf '%s\\n' "
        + publicKey
        + " >> \"$home/.ssh/authorized_keys\";"
        + " chown -R \"$user:$user\" \"$home/.ssh\" 2>/dev/null"
        + " || chown -R \"$user\" \"$home/.ssh\";"
        + " chmod 700 \"$home/.ssh\";"
        + " chmod 600 \"$home/.ssh/authorized_keys\";"
        + " ssh-keygen -A >/dev/null 2>&1 || true;"
        + " pgrep -x sshd >/dev/null || \"$sshd_path\"";
  }

  /**
   * Whether this agent already owns a sandbox worth reconnecting to rather than replacing.
   *
   * <p>Separated from {@link #launch} so the decision can be tested without a tunnel or an SSH
   * handshake: getting it wrong either strands a billed sandbox or throws away the workspace a
   * build is resuming into.
   */
  static boolean adoptsExistingSandbox(CreateOSSlave slave, CreateOSApiClient apiClient) {
    String sandboxId = slave.getSandboxId();
    return sandboxId != null && apiClient.isRunning(sandboxId);
  }
}
