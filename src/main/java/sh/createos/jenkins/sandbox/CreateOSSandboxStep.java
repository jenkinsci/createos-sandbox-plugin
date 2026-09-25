package sh.createos.jenkins.sandbox;

import hudson.Extension;
import hudson.model.TaskListener;
import java.io.IOException;
import java.io.Serializable;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest2;

/** Pipeline block step that creates a CreateOS sandbox for exec-mode child steps. */
public class CreateOSSandboxStep extends Step implements Serializable, CreateOSTemplateOverrides {

  private static final long serialVersionUID = 1L;

  private String cloud;
  private String inheritFrom;
  private String shape;
  private String rootfs;
  private String region;
  private int diskMiB;
  private List<String> networks;
  private List<CreateOSDiskAttachment> disks;

  /** Creates a Pipeline step configured by databound setters. */
  @DataBoundConstructor
  public CreateOSSandboxStep() {}

  public String getCloud() {
    return cloud;
  }

  @DataBoundSetter
  public void setCloud(String cloud) {
    this.cloud = cloud;
  }

  public String getInheritFrom() {
    return inheritFrom;
  }

  @DataBoundSetter
  public void setInheritFrom(String inheritFrom) {
    this.inheritFrom = inheritFrom;
  }

  public String getShape() {
    return shape;
  }

  @DataBoundSetter
  public void setShape(String shape) {
    this.shape = shape;
  }

  public String getRootfs() {
    return rootfs;
  }

  @DataBoundSetter
  public void setRootfs(String rootfs) {
    this.rootfs = rootfs;
  }

  public String getRegion() {
    return region;
  }

  @DataBoundSetter
  public void setRegion(String region) {
    this.region = region;
  }

  public int getDiskMiB() {
    return diskMiB;
  }

  @DataBoundSetter
  public void setDiskMiB(int diskMiB) {
    this.diskMiB = diskMiB;
  }

  public List<String> getNetworks() {
    return networks;
  }

  @DataBoundSetter
  public void setNetworks(List<String> networks) {
    this.networks = networks;
  }

  public List<CreateOSDiskAttachment> getDisks() {
    return disks;
  }

  @DataBoundSetter
  public void setDisks(List<CreateOSDiskAttachment> disks) {
    this.disks = disks;
  }

  @Override
  public StepExecution start(StepContext context) {
    return new Execution(context, this);
  }

  private static class Execution extends StepExecution {

    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(Execution.class.getName());

    private final CreateOSSandboxStep step;
    private String sandboxId;
    private String sandboxName;

    Execution(StepContext context, CreateOSSandboxStep step) {
      super(context);
      this.step = step;
    }

    @Override
    public boolean start() throws Exception {
      TaskListener listener = getContext().get(TaskListener.class);
      CreateOSCloud cloud = CreateOSStepSupport.resolveCloud(step.getCloud());
      sandboxName = CreateOSSlave.sandboxName("exec-" + UUID.randomUUID());
      if (!cloud.reserveExecSandbox(sandboxName)) {
        throw new IOException(
            "CreateOS exec sandbox cap reached for cloud '"
                + cloud.name
                + "' ("
                + cloud.getExecSandboxCap()
                + ")");
      }

      boolean bodyStarted = false;
      CreateOSApiClient client = null;
      try {
        CreateOSSandboxRequest request =
            CreateOSStepSupport.requestFromStep(step).withName(sandboxName);
        client = cloud.buildApiClient();
        listener.getLogger().println("=== CreateOS Sandbox Exec Mode ===");
        listener.getLogger().println("Shape: " + request.shape());
        listener.getLogger().println("RootFS: " + request.rootfs());
        // Parity with CreateOSLauncher, which prints these before creating an agent sandbox.
        // Without them a dropped `disks:` or `networks:` is invisible: the sandbox comes up
        // healthy, the commands run, and the only symptom is a mount path that does not exist.
        if (!request.networkIds().isEmpty()) {
          listener.getLogger().println("Private networks: " + request.networkIds());
        }
        listener.getLogger().println("Disk attachments: " + request.disks().size());
        listener.getLogger().println("Creating sandbox...");
        sandboxId = client.createSandbox(request);
        listener.getLogger().println("Sandbox created: " + sandboxId);
        listener.getLogger().println("Waiting for sandbox to be ready...");
        client.waitForRunning(sandboxId, Duration.ofMinutes(5));
        listener.getLogger().println("Sandbox is running.");

        CreateOSSandboxContext sandboxContext = new CreateOSSandboxContext(sandboxId, cloud.name);
        getContext()
            .newBodyInvoker()
            .withContext(sandboxContext)
            .withCallback(new CleanupCallback(sandboxContext, sandboxName))
            .start();
        bodyStarted = true;
        return false;
      } finally {
        if (!bodyStarted) {
          cleanupAfterFailedStart(client, cloud, listener);
        }
      }
    }

    private void cleanupAfterFailedStart(
        CreateOSApiClient client, CreateOSCloud cloud, TaskListener listener) {
      try {
        if (sandboxId != null) {
          listener.getLogger().println("Destroying sandbox after failed startup: " + sandboxId);
          client.destroySandbox(sandboxId);
          sandboxId = null;
        }
      } catch (Exception e) {
        LOGGER.log(Level.WARNING, "Failed to destroy sandbox after startup failure", e);
      } finally {
        cloud.releaseExecSandbox(sandboxName);
      }
    }

    @Override
    public void onResume() {
      try {
        CreateOSStepSupport.resolveCloud(step.getCloud()).restoreExecSandbox(sandboxName);
      } catch (RuntimeException e) {
        LOGGER.log(Level.WARNING, "Failed to restore exec sandbox reservation", e);
      }
    }

    @Override
    public void stop(Throwable cause) throws Exception {
      if (sandboxId != null) {
        try {
          CreateOSCloud cloud = CreateOSStepSupport.resolveCloud(step.getCloud());
          cloud.buildApiClient().destroySandbox(sandboxId);
          sandboxId = null;
        } catch (IOException | RuntimeException e) {
          LOGGER.log(Level.WARNING, "Failed to destroy CreateOS sandbox after stop", e);
        }
      }
      try {
        CreateOSStepSupport.resolveCloud(step.getCloud()).releaseExecSandbox(sandboxName);
      } catch (RuntimeException e) {
        LOGGER.log(Level.WARNING, "Failed to release exec sandbox reservation after stop", e);
      }
      super.stop(cause);
    }
  }

  private static class CleanupCallback extends BodyExecutionCallback {

    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(CleanupCallback.class.getName());

    private final CreateOSSandboxContext sandboxContext;
    private final String sandboxName;

    CleanupCallback(CreateOSSandboxContext sandboxContext, String sandboxName) {
      this.sandboxContext = sandboxContext;
      this.sandboxName = sandboxName;
    }

    @Override
    public void onSuccess(StepContext context, Object result) {
      cleanup(context);
      context.onSuccess(result);
    }

    @Override
    public void onFailure(StepContext context, Throwable t) {
      cleanup(context);
      context.onFailure(t);
    }

    private void cleanup(StepContext context) {
      try {
        TaskListener listener = context.get(TaskListener.class);
        listener.getLogger().println("Destroying CreateOS sandbox: " + sandboxContext.sandboxId());
        CreateOSCloud cloud = CreateOSStepSupport.resolveCloud(sandboxContext.cloudName());
        cloud.buildApiClient().destroySandbox(sandboxContext.sandboxId());
      } catch (Exception e) {
        LOGGER.log(Level.WARNING, "Failed to destroy CreateOS sandbox", e);
      } finally {
        try {
          CreateOSStepSupport.resolveCloud(sandboxContext.cloudName())
              .releaseExecSandbox(sandboxName);
        } catch (RuntimeException e) {
          LOGGER.log(Level.WARNING, "Failed to release exec sandbox reservation", e);
        }
      }
    }
  }

  /** Descriptor for the createosSandbox Pipeline step. */
  @Extension
  public static class DescriptorImpl extends StepDescriptor {

    @Override
    public Step newInstance(StaplerRequest2 request, JSONObject form) throws FormException {
      CreateOSStepSupport.parseListField(form, "networks");
      return super.newInstance(request, form);
    }

    @Override
    public String getFunctionName() {
      return "createosSandbox";
    }

    @Override
    public String getDisplayName() {
      return "CreateOS sandbox";
    }

    @Override
    public boolean takesImplicitBlockArgument() {
      return true;
    }

    @Override
    public Set<? extends Class<?>> getRequiredContext() {
      return Set.of(TaskListener.class);
    }

    @Override
    public Set<? extends Class<?>> getProvidedContext() {
      return Set.of(CreateOSSandboxContext.class);
    }
  }
}
