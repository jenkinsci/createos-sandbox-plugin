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
