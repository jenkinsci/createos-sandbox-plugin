package sh.createos.jenkins.sandbox;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

/**
 * Configuration template for a CreateOS Sandbox agent.
 *
 * <p>Like PodTemplate in the Kubernetes plugin — defines what kind of sandbox to create. In Go
 * terms, this is a config struct that gets serialized to/from Jenkins XML.
 *
 * <p>Users configure these in Jenkins UI under Manage Jenkins > Clouds > CreateOS.
 */
public class SandboxTemplate extends AbstractDescribableImpl<SandboxTemplate>
    implements Serializable {

  private static final long serialVersionUID = 1L;

  /** Jenkins label — jobs with `agent { label 'createos' }` match this. */
  private final String label;

  /** Sandbox VM shape — e.g. "s-1vcpu-1gb", "s-4vcpu-4gb". Like instance type in AWS. */
  private final String shape;

  /** Base image — e.g. "devbox:1", "ubuntu:22.04". Like `FROM` in a Dockerfile. */
  private final String rootfs;

  /** Remote filesystem path inside sandbox for Jenkins workspace. */
  private String remoteFs = "/home/jenkins";

  /** Optional CreateOS region. Empty means the API default region. */
  private String region;

  /** Optional root disk size in MiB. 0 means the CreateOS shape default. */
  private int diskMiB;

  /** Agent launch transport. Defaults to the existing inbound WebSocket behavior. */
  private CreateOSLaunchMethod launcher;

  /** Transport name written by plugin versions before the launch method became describable. */
  @Deprecated private transient String launchMethod;

  /** SSH credential written by plugin versions before the launch method became describable. */
  @Deprecated private transient String sshCredentialsId;

  /** Public key written by plugin versions that could not derive it from the credential. */
  @Deprecated private transient String sshPublicKey;

  /** Whether Jenkinsfiles may override this template through the createos Declarative agent. */
  private Boolean allowPipelineOverrides;

  /** Existing CreateOS private network ids or names, separated by whitespace or commas. */
  private String networks;

  /** S3 disk attachments to mount at sandbox creation. */
  private List<CreateOSDiskAttachment> disks = new ArrayList<>();

  /**
   * Minutes an agent may sit idle before its sandbox is deleted. Null in templates saved before the
   * field existed, which read as {@link #DEFAULT_IDLE_MINUTES}.
   */
  private Integer idleMinutes;

  /**
   * Whether a controller restart deletes this template's agents instead of reconnecting them. Off
   * by default: a restart would otherwise re-run every in-flight build from scratch, on fresh
   * sandboxes billed a second time.
   */
  private boolean deleteOnRestart;

  /**
   * Whether an agent stays up to take further builds instead of being deleted after its first. Its
   * sandbox keeps billing while idle, until the idle timeout; a timeout of 0 keeps it forever.
   */
  private boolean reuseAgent;

  static final int DEFAULT_IDLE_MINUTES = 1;

  /** Creates a sandbox template with its required Jenkins label, shape, and root filesystem. */
  @DataBoundConstructor
  public SandboxTemplate(String label, String shape, String rootfs) {
    this.label = label;
    this.shape = shape;
    this.rootfs = rootfs;
  }

  public String getLabel() {
    return label;
  }

  public String getShape() {
    return shape;
  }

  public String getRootfs() {
    return rootfs;
  }

  public String getRemoteFs() {
    return remoteFs;
  }

  @DataBoundSetter
  public void setRemoteFs(String remoteFs) {
    this.remoteFs = remoteFs;
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

  public CreateOSLaunchMethod getLauncher() {
    return launcher == null ? new InboundLaunchMethod() : launcher;
  }

  @DataBoundSetter
  public void setLauncher(CreateOSLaunchMethod launcher) {
    this.launcher = launcher;
  }

  /** Returns the SSH transport, or null when this template launches inbound agents. */
  SshLaunchMethod sshLauncher() {
    return getLauncher() instanceof SshLaunchMethod ssh ? ssh : null;
  }

  /**
   * Migrates templates written before the launch method became describable.
   *
   * <p>The old form stored the transport as a string beside two SSH fields that were meaningless
   * for inbound agents; the public key is now derived from the credential, so it is dropped.
   */
  protected Object readResolve() {
    if (launcher == null) {
      launcher =
          "ssh".equals(launchMethod)
              ? new SshLaunchMethod(sshCredentialsId)
              : new InboundLaunchMethod();
    }
    return this;
  }

  public boolean isAllowPipelineOverrides() {
    return allowPipelineOverrides == null || allowPipelineOverrides;
  }

  void rejectPipelineOverrides() {
    if (!isAllowPipelineOverrides()) {
      throw new IllegalStateException(pipelineOverridesDisabledMessage());
    }
  }

  String pipelineOverridesDisabledMessage() {
    return "CreateOS Pipeline-defined overrides are disabled for template: " + label;
  }

  @DataBoundSetter
  public void setAllowPipelineOverrides(boolean allowPipelineOverrides) {
    this.allowPipelineOverrides = allowPipelineOverrides;
  }

  public String getNetworks() {
    return networks;
  }

  @DataBoundSetter
  public void setNetworks(String networks) {
    this.networks = networks;
  }

  /** Returns configured private network ids or names as normalized request values. */
  public List<String> getNetworkIdList() {
    if (networks == null || networks.isBlank()) {
      return Collections.emptyList();
    }
    return Arrays.stream(networks.trim().split("[\\s,]+"))
        .filter(value -> !value.isBlank())
        .distinct()
        .toList();
  }

  public List<CreateOSDiskAttachment> getDisks() {
    return disks;
  }

  @DataBoundSetter
  public void setDisks(List<CreateOSDiskAttachment> disks) {
    this.disks = disks != null ? disks : new ArrayList<>();
  }

  /** 0 means never idle out, which only a reused agent may do: a one-shot agent would leak. */
  public int getIdleMinutes() {
    if (idleMinutes == null || idleMinutes < 0 || (idleMinutes == 0 && !reuseAgent)) {
      return DEFAULT_IDLE_MINUTES;
    }
    return idleMinutes;
  }

  @DataBoundSetter
  public void setIdleMinutes(int idleMinutes) {
    this.idleMinutes = idleMinutes;
  }

  public boolean isDeleteOnRestart() {
    return deleteOnRestart;
  }

  @DataBoundSetter
  public void setDeleteOnRestart(boolean deleteOnRestart) {
    this.deleteOnRestart = deleteOnRestart;
  }

  public boolean isReuseAgent() {
    return reuseAgent;
  }

  @DataBoundSetter
  public void setReuseAgent(boolean reuseAgent) {
    this.reuseAgent = reuseAgent;
  }

  SandboxTemplate copyForPipeline(String label, String shape, String rootfs) {
    SandboxTemplate copy = new SandboxTemplate(label, shape, rootfs);
    copy.setRemoteFs(remoteFs);
    copy.setRegion(region);
    copy.setDiskMiB(diskMiB);
    copy.setLauncher(getLauncher());
    copy.setAllowPipelineOverrides(isAllowPipelineOverrides());
    copy.setNetworks(networks);
    copy.setDisks(new ArrayList<>(disks));
    copy.setIdleMinutes(getIdleMinutes());
    copy.setDeleteOnRestart(deleteOnRestart);
    copy.setReuseAgent(reuseAgent);
    return copy;
  }

  /** Exposes sandbox template metadata and validation to Jenkins. */
  @Extension
  public static class DescriptorImpl extends Descriptor<SandboxTemplate> {

    @Override
    public String getDisplayName() {
      return "CreateOS Sandbox Template";
    }

    /** Validates the optional root disk size override. */
    public FormValidation doCheckDiskMiB(@QueryParameter int value) {
      if (value < 0) {
        return FormValidation.error("Disk size cannot be negative");
      }
      return FormValidation.ok();
    }

    /** Rejects an idle timeout that would delete an agent before it could take a build. */
    public FormValidation doCheckIdleMinutes(
        @QueryParameter int value, @QueryParameter boolean reuseAgent) {
      if (value < 0 || (value == 0 && !reuseAgent)) {
        return FormValidation.error("Idle timeout must be at least 1 minute");
      }
      if (value == 0) {
        return FormValidation.warning(
            "Agents are never deleted: their sandboxes keep billing until removed by hand");
      }
      return FormValidation.ok();
    }
  }
}
