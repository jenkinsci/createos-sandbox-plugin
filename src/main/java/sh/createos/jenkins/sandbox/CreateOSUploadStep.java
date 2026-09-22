package sh.createos.jenkins.sandbox;

import hudson.Extension;
import hudson.FilePath;
import hudson.model.TaskListener;
import java.io.InputStream;
import java.io.Serializable;
import java.util.List;
import java.util.Set;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest2;

/** Pipeline step that uploads workspace files into the active CreateOS sandbox. */
public class CreateOSUploadStep extends Step implements Serializable {

  private static final long serialVersionUID = 1L;

  private final String source;
  private final String target;
  private List<String> excludes;

  /** Creates an upload step from a workspace source to an absolute sandbox target. */
  @DataBoundConstructor
  public CreateOSUploadStep(String source, String target) {
    this.source = source;
    this.target = target;
  }

  public String getSource() {
    return source;
  }

  public String getTarget() {
    return target;
  }

  public List<String> getExcludes() {
    return excludes;
  }

  @DataBoundSetter
  public void setExcludes(List<String> excludes) {
    this.excludes = excludes;
  }

  @Override
  public StepExecution start(StepContext context) {
    return new Execution(context, this);
  }

  private static class Execution extends SynchronousNonBlockingStepExecution<Void> {

    private static final long serialVersionUID = 1L;

    private final CreateOSUploadStep step;

    Execution(StepContext context, CreateOSUploadStep step) {
      super(context);
      this.step = step;
    }

    @Override
    protected Void run() throws Exception {
      CreateOSStepSupport.requireAbsoluteRemotePath(step.getTarget(), "target");
      CreateOSSandboxContext sandboxContext = getContext().get(CreateOSSandboxContext.class);
      FilePath workspace = getContext().get(FilePath.class);
      TaskListener listener = getContext().get(TaskListener.class);
      CreateOSApiClient client =
          CreateOSStepSupport.resolveCloud(sandboxContext.cloudName()).buildApiClient();

      FilePath sourcePath = CreateOSStepSupport.workspacePath(workspace, step.getSource());
      if (!sourcePath.exists()) {
        throw new IllegalArgumentException("Upload source does not exist: " + step.getSource());
      }

      if (sourcePath.isDirectory()) {
        uploadDirectory(sourcePath, client, sandboxContext, listener);
      } else {
        uploadOne(sourcePath, step.getTarget(), client, sandboxContext, listener);
      }
      return null;
    }

    private void uploadDirectory(
        FilePath sourcePath,
        CreateOSApiClient client,
        CreateOSSandboxContext sandboxContext,
        TaskListener listener)
        throws Exception {
      String excludes = step.getExcludes() == null ? null : String.join(",", step.getExcludes());
      FilePath[] files = sourcePath.list("**", excludes, false);
      int uploaded = 0;
      String base = sourcePath.absolutize().getRemote();
      for (FilePath file : files) {
        if (file.isDirectory()) {
          continue;
        }
        String relative = relativize(base, file.absolutize().getRemote());
        String remotePath = CreateOSStepSupport.joinRemotePath(step.getTarget(), relative);
        uploadOne(file, remotePath, client, sandboxContext, listener);
        uploaded++;
      }
      listener.getLogger().println("Uploaded " + uploaded + " file(s) to " + step.getTarget());
    }

    private void uploadOne(
        FilePath file,
        String remotePath,
        CreateOSApiClient client,
        CreateOSSandboxContext sandboxContext,
        TaskListener listener)
        throws Exception {
      try (InputStream input = file.read()) {
        client.uploadFile(sandboxContext.sandboxId(), remotePath, input);
      }
      if ((file.mode() & 0111) != 0) {
        client.runShellScript(
            sandboxContext.sandboxId(),
            "chmod +x " + CreateOSLauncher.shellQuote(remotePath),
            listener.getLogger(),
            false);
      }
    }

    private String relativize(String base, String child) {
      String normalizedBase = base.endsWith("/") ? base : base + "/";
      if (child.startsWith(normalizedBase)) {
        return child.substring(normalizedBase.length());
      }
      return child;
    }
  }

  /** Descriptor for the createosUpload Pipeline step. */
  @Extension
  public static class DescriptorImpl extends StepDescriptor {

    @Override
    public Step newInstance(StaplerRequest2 request, JSONObject form) throws FormException {
      CreateOSStepSupport.parseListField(form, "excludes");
      return super.newInstance(request, form);
    }

    @Override
    public String getFunctionName() {
      return "createosUpload";
    }

    @Override
    public String getDisplayName() {
      return "Upload files to CreateOS sandbox";
    }

    @Override
    public Set<? extends Class<?>> getRequiredContext() {
      return Set.of(CreateOSSandboxContext.class, FilePath.class, TaskListener.class);
    }
  }
}
