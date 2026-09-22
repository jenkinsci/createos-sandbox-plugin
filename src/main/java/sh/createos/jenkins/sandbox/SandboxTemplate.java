package sh.createos.jenkins.sandbox;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

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
  static final String LAUNCH_METHOD_INBOUND = "inbound";
  static final String LAUNCH_METHOD_SSH = "ssh";

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
  private String launchMethod = LAUNCH_METHOD_INBOUND;

  /** Jenkins SSH credential used when launchMethod is ssh. */
  private String sshCredentialsId;

  /** Public key matching sshCredentialsId, injected into the sandbox before sshd starts. */
  // lgtm[jenkins/plaintext-storage] An OpenSSH public key is intended to be shared.
  private String sshPublicKey;

  /** Whether Jenkinsfiles may override this template through the createos Declarative agent. */
  private Boolean allowPipelineOverrides;

  /** Existing CreateOS private network ids or names, separated by whitespace or commas. */
  private String networks;

  /** S3 disk attachments to mount at sandbox creation. */
  private List<CreateOSDiskAttachment> disks = new ArrayList<>();

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

  public String getLaunchMethod() {
    return launchMethod == null ? LAUNCH_METHOD_INBOUND : launchMethod;
  }

  @DataBoundSetter
  public void setLaunchMethod(String launchMethod) {
    this.launchMethod = validateLaunchMethod(launchMethod);
  }

  boolean isSshLaunch() {
    return LAUNCH_METHOD_SSH.equals(getLaunchMethod());
  }

  public String getSshCredentialsId() {
    return sshCredentialsId;
  }

  @DataBoundSetter
  public void setSshCredentialsId(String sshCredentialsId) {
    this.sshCredentialsId = sshCredentialsId;
  }

  public String getSshPublicKey() {
    return sshPublicKey;
  }

  @DataBoundSetter
  public void setSshPublicKey(String sshPublicKey) {
    this.sshPublicKey = sshPublicKey;
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

  SandboxTemplate copyForPipeline(String label, String shape, String rootfs) {
    SandboxTemplate copy = new SandboxTemplate(label, shape, rootfs);
    copy.setRemoteFs(remoteFs);
    copy.setRegion(region);
    copy.setDiskMiB(diskMiB);
    copy.setLaunchMethod(getLaunchMethod());
    copy.setSshCredentialsId(sshCredentialsId);
    copy.setSshPublicKey(sshPublicKey);
    copy.setAllowPipelineOverrides(isAllowPipelineOverrides());
    copy.setNetworks(networks);
    copy.setDisks(new ArrayList<>(disks));
    return copy;
  }

  static String validateLaunchMethod(String launchMethod) {
    if (launchMethod == null || launchMethod.isBlank()) {
      return LAUNCH_METHOD_INBOUND;
    }
    String normalized = launchMethod.trim();
    if (LAUNCH_METHOD_INBOUND.equals(normalized) || LAUNCH_METHOD_SSH.equals(normalized)) {
      return normalized;
    }
    throw new IllegalArgumentException("Unsupported CreateOS launch method: " + launchMethod);
  }

  /** Exposes sandbox template metadata and validation to Jenkins. */
  @Extension
  public static class DescriptorImpl extends Descriptor<SandboxTemplate> {

    @Override
    public String getDisplayName() {
      return "CreateOS Sandbox Template";
    }

    /** Validates that a Jenkins label was provided. */
    @POST
    public FormValidation doCheckLabel(@QueryParameter String value) {
      Jenkins.get().checkPermission(Jenkins.ADMINISTER);
      if (value == null || value.isBlank()) {
        return FormValidation.error("Label is required");
      }
      return FormValidation.ok();
    }

    /** Validates that a CreateOS sandbox shape was provided. */
    @POST
    public FormValidation doCheckShape(@QueryParameter String value) {
      Jenkins.get().checkPermission(Jenkins.ADMINISTER);
      if (value == null || value.isBlank()) {
        return FormValidation.error("Shape is required (e.g. s-1vcpu-1gb)");
      }
      return FormValidation.ok();
    }

    /** Validates that a CreateOS root filesystem was provided. */
    @POST
    public FormValidation doCheckRootfs(@QueryParameter String value) {
      Jenkins.get().checkPermission(Jenkins.ADMINISTER);
      if (value == null || value.isBlank()) {
        return FormValidation.error("Root filesystem is required (e.g. devbox:1)");
      }
      return FormValidation.ok();
    }

    /** Validates the optional root disk size override. */
    public FormValidation doCheckDiskMiB(@QueryParameter int value) {
      if (value < 0) {
        return FormValidation.error("Disk size cannot be negative");
      }
      return FormValidation.ok();
    }

    /** Returns the supported agent launch transports. */
    public ListBoxModel doFillLaunchMethodItems() {
      ListBoxModel items = new ListBoxModel();
      items.add("Inbound WebSocket", LAUNCH_METHOD_INBOUND);
      items.add("SSH over CreateOS tunnel", LAUNCH_METHOD_SSH);
      return items;
    }

    /** Validates that the launch method is one of the supported transports. */
    @POST
    public FormValidation doCheckLaunchMethod(@QueryParameter String value) {
      Jenkins.get().checkPermission(Jenkins.ADMINISTER);
      try {
        validateLaunchMethod(value);
        return FormValidation.ok();
      } catch (IllegalArgumentException e) {
        return FormValidation.error(e.getMessage());
      }
    }

    /** Returns SSH credentials available to Jenkins administrators. */
    @POST
    public ListBoxModel doFillSshCredentialsIdItems(@QueryParameter String sshCredentialsId) {
      if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
        return new StandardListBoxModel().includeCurrentValue(sshCredentialsId);
      }
      return new StandardListBoxModel()
          .includeEmptyValue()
          .includeCurrentValue(sshCredentialsId)
          .includeMatchingAs(
              ACL.SYSTEM2,
              Jenkins.get(),
              SSHUserPrivateKey.class,
              Collections.emptyList(),
              CredentialsMatchers.always());
    }

    /** Validates the optional public key used for SSH launch. */
    @POST
    public FormValidation doCheckSshPublicKey(@QueryParameter String value) {
      Jenkins.get().checkPermission(Jenkins.ADMINISTER);
      if (value == null || value.isBlank()) {
        return FormValidation.ok();
      }
      String trimmed = value.trim();
      if (!trimmed.startsWith("ssh-ed25519 ")
          && !trimmed.startsWith("ssh-rsa ")
          && !trimmed.startsWith("ecdsa-sha2-")) {
        return FormValidation.error("Expected an OpenSSH public key");
      }
      return FormValidation.ok();
    }
  }
}
