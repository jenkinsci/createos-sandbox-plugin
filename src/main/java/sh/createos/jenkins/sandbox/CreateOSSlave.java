package sh.createos.jenkins.sandbox;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.durabletask.executors.OnceRetentionStrategy;

/** A one-executor Jenkins agent backed by an ephemeral CreateOS sandbox. */
public class CreateOSSlave extends AbstractCloudSlave {

  private static final long serialVersionUID = 1L;
  private static final Logger LOGGER = Logger.getLogger(CreateOSSlave.class.getName());

  private static final String SANDBOX_NAME_PREFIX = "jenkins-";

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

    // OnceRetentionStrategy: agent accepts one build then terminates.
    // Timeout of 5 minutes in case agent never gets a build assigned.
    setRetentionStrategy(new OnceRetentionStrategy(5));
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

  /** Whether this agent is reached over SSH rather than an inbound WebSocket. */
  boolean isSshLaunch() {
    return template.sshLauncher() != null;
  }

  /**
   * Names the sandbox after both the agent and this controller.
   *
   * <p>The controller half matters when several Jenkins instances share one CreateOS account: the
   * orphan sweep destroys sandboxes whose agent is gone, and without it one controller would reap
   * another's running agents.
   */
  static String sandboxName(String agentName) {
    return sandboxNamePrefix() + agentName;
  }

  /** The prefix every sandbox created by this controller carries. */
  static String sandboxNamePrefix() {
    String instanceId = Jenkins.get().getLegacyInstanceId();
    return SANDBOX_NAME_PREFIX + instanceId.substring(0, Math.min(8, instanceId.length())) + "-";
  }

  /** Recovers the agent name from a sandbox this controller named, or null if it did not. */
  static String agentNameOf(String sandboxName) {
    String prefix = sandboxNamePrefix();
    return sandboxName != null && sandboxName.startsWith(prefix)
        ? sandboxName.substring(prefix.length())
        : null;
  }

  public void setSandboxId(String sandboxId) {
    this.sandboxId = sandboxId;
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
