package sh.createos.jenkins.sandbox;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.CloudRetentionStrategy;
import hudson.slaves.RetentionStrategy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.durabletask.executors.OnceRetentionStrategy;

/** A one-executor Jenkins agent backed by an ephemeral CreateOS sandbox. */
public class CreateOSSlave extends AbstractCloudSlave {

  private static final long serialVersionUID = 1L;
  private static final Logger LOGGER = Logger.getLogger(CreateOSSlave.class.getName());

  /** The CreateOS API rejects a longer sandbox name outright, failing every agent launch. */
  private static final int SANDBOX_NAME_MAX = 22;

  private static final String SANDBOX_NAME_PREFIX = "jk-";
  private static final int SANDBOX_NAME_SUFFIX_LENGTH =
      SANDBOX_NAME_MAX - SANDBOX_NAME_PREFIX.length() - 6 - 1;

  private volatile String sandboxId;
  private final SandboxTemplate template;
  private final String cloudName;
  private transient CreateOSTunnelProxy tunnelProxy;

  /** Creates an agent node from a sandbox template owned by the given cloud. */
  public CreateOSSlave(String name, SandboxTemplate template, CreateOSCloud cloud)
      throws Descriptor.FormException, IOException {
    super(name, template.getRemoteFs(), new CreateOSLauncher());
    this.template = template;
    this.cloudName = cloud.name;

    setLabelString(template.getLabel());
    setMode(Node.Mode.EXCLUSIVE);
    setNumExecutors(1);

    setRetentionStrategy(retentionStrategy(template));
  }

  /**
   * One-shot agents take one build and are reaped after the idle timeout if they never get one.
   * Reused agents take builds until idle for the timeout, or forever when it is 0.
   */
  static RetentionStrategy<?> retentionStrategy(SandboxTemplate template) {
    int idleMinutes = template.getIdleMinutes();
    if (!template.isReuseAgent()) {
      return new OnceRetentionStrategy(idleMinutes);
    }
    return idleMinutes == 0
        ? new RetentionStrategy.Always()
        : new CloudRetentionStrategy(idleMinutes);
  }

  @Override
  public AbstractCloudComputer createComputer() {
    return new CreateOSComputer(this);
  }

  @Override
  protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
    terminateBackingSandbox(listener);
  }

  synchronized void terminateBackingSandbox(TaskListener listener)
      throws IOException, InterruptedException {
    listener.getLogger().println("Terminating CreateOS sandbox agent: " + getNodeName());

    if (sandboxId == null) {
      listener.getLogger().println("No sandbox to destroy (never launched).");
      return;
    }

    try {
      closeTunnelProxy();
      CreateOSCloud cloud = getCreateOSCloud();
      CreateOSApiClient apiClient = cloud.buildApiClient();

      listener.getLogger().println("Destroying sandbox: " + sandboxId);
      apiClient.destroySandbox(sandboxId);
      sandboxId = null;
      listener.getLogger().println("Sandbox destroyed.");
    } catch (Exception e) {
      listener.getLogger().println("WARNING: Failed to destroy sandbox: " + e.getMessage());
      LOGGER.log(Level.WARNING, "Failed to destroy sandbox " + sandboxId, e);
    }
  }

  void setTunnelProxy(CreateOSTunnelProxy tunnelProxy) {
    this.tunnelProxy = tunnelProxy;
  }

  void closeTunnelProxy() {
    if (tunnelProxy != null) {
      tunnelProxy.close();
      tunnelProxy = null;
    }
  }

  public CreateOSCloud getCreateOSCloud() {
    return (CreateOSCloud) Jenkins.get().getCloud(cloudName);
  }

  String getCloudName() {
    return cloudName;
  }

  public String getSandboxId() {
    return sandboxId;
  }

  /**
   * Names the sandbox so this controller can recognise it later.
   *
   * <p>The CreateOS API caps a sandbox name at {@value #SANDBOX_NAME_MAX} characters, which is far
   * too short to carry an agent name — so the name identifies the controller and nothing else, and
   * the agent it belongs to is matched by sandbox id, which the node persists. The suffix is a
   * digest of the agent name purely to keep names unique and reproducible; nothing reads it back.
   *
   * <p>The controller half matters when several Jenkins instances share one CreateOS account: the
   * orphan sweep destroys sandboxes whose agent is gone, and without it one controller would reap
   * another's running agents.
   */
  static String sandboxName(String agentName) {
    return sandboxNamePrefix() + digest(agentName, SANDBOX_NAME_SUFFIX_LENGTH);
  }

  /** The prefix every sandbox created by this controller carries. */
  static String sandboxNamePrefix() {
    return SANDBOX_NAME_PREFIX + digest(Jenkins.get().getLegacyInstanceId(), 6) + "-";
  }

  /** Whether a sandbox was named by this controller, and so is this controller's to reclaim. */
  static boolean namedByThisController(String sandboxName) {
    return sandboxName != null && sandboxName.startsWith(sandboxNamePrefix());
  }

  /**
   * Lowercase hex of a SHA-256 prefix. Only has to be stable and collision-free in practice, not
   * secret — the instance id it is derived from is not one either, but hashing keeps the name
   * inside the API's length cap and its character set.
   */
  private static String digest(String value, int length) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash).substring(0, length);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by every JVM", e);
    }
  }

  /**
   * Records which sandbox backs this agent, and writes it to disk immediately.
   *
   * <p>The save is what makes a restart survivable. The node is persisted when it is added, which
   * happens before the launcher has created the sandbox, so without saving again the id is only
   * ever in memory: after a restart the restored node knows of no sandbox, recovery finds nothing
   * to reconnect to, and the agent is terminated while its sandbox keeps running.
   */
  public void setSandboxId(String sandboxId) {
    this.sandboxId = sandboxId;
    try {
      save();
    } catch (IOException e) {
      LOGGER.log(Level.WARNING, "Could not persist sandbox id for " + getNodeName(), e);
    }
  }

  public SandboxTemplate getTemplate() {
    return template;
  }

  /** Describes CreateOS agent nodes to Jenkins. */
  @Extension
  public static class DescriptorImpl extends SlaveDescriptor {
    @Override
    public String getDisplayName() {
      return "CreateOS Sandbox Agent";
    }

    @Override
    public boolean isInstantiable() {
      return false;
    }
  }
}
