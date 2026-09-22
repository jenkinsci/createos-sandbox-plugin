package sh.createos.jenkins.sandbox;

import hudson.Extension;
import hudson.FilePath;
import hudson.model.TaskListener;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.Set;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

/** Pipeline step that downloads one file from the active CreateOS sandbox. */
public class CreateOSDownloadStep extends Step implements Serializable {

  private static final long serialVersionUID = 1L;

  private final String source;
  private final String target;

  /** Creates a download step from an absolute sandbox source to a workspace target. */
  @DataBoundConstructor
  public CreateOSDownloadStep(String source, String target) {
    this.source = source;
    this.target = target;
  }

  public String getSource() {
    return source;
  }

  public String getTarget() {
    return target;
  }

  @Override
  public StepExecution start(StepContext context) {
    return new Execution(context, this);
  }

  private static class Execution extends SynchronousNonBlockingStepExecution<Void> {

    private static final long serialVersionUID = 1L;

    private final CreateOSDownloadStep step;

    Execution(StepContext context, CreateOSDownloadStep step) {
      super(context);
      this.step = step;
    }

    @Override
    protected Void run() throws Exception {
      CreateOSStepSupport.requireAbsoluteRemotePath(step.getSource(), "source");
      CreateOSSandboxContext sandboxContext = getContext().get(CreateOSSandboxContext.class);
      FilePath workspace = getContext().get(FilePath.class);
      CreateOSApiClient client =
          CreateOSStepSupport.resolveCloud(sandboxContext.cloudName()).buildApiClient();

      FilePath targetPath = CreateOSStepSupport.workspacePath(workspace, step.getTarget());
      FilePath parent = targetPath.getParent();
      if (parent != null) {
        parent.mkdirs();
      }
      // Recheck after creating parents so a symlink cannot redirect the write.
      targetPath = CreateOSStepSupport.workspacePath(workspace, step.getTarget());
      try (OutputStream output = targetPath.write()) {
        client.downloadFile(sandboxContext.sandboxId(), step.getSource(), output);
      }
      TaskListener listener = getContext().get(TaskListener.class);
      listener.getLogger().println("Downloaded " + step.getSource() + " to " + step.getTarget());
      return null;
    }
  }

  /** Descriptor for the createosDownload Pipeline step. */
  @Extension
  public static class DescriptorImpl extends StepDescriptor {

    @Override
    public String getFunctionName() {
      return "createosDownload";
    }

    @Override
    public String getDisplayName() {
      return "Download file from CreateOS sandbox";
    }

    @Override
    public Set<? extends Class<?>> getRequiredContext() {
      return Set.of(CreateOSSandboxContext.class, FilePath.class, TaskListener.class);
    }
  }
}
