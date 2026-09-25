package sh.createos.jenkins.sandbox;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import java.io.Serializable;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/** User-facing CreateOS disk attachment configuration. */
public class CreateOSDiskAttachment extends AbstractDescribableImpl<CreateOSDiskAttachment>
    implements Serializable {

  private static final long serialVersionUID = 1L;

  /** ID or name of a disk that already exists in CreateOS. Does not create a new disk. */
  private final String id;

  /** Absolute mount path inside the sandbox. */
  private final String mountPath;

  /** Optional prefix inside the disk bucket. */
  private String subPath;

  /** Creates a disk attachment with the required disk id/name and mount path. */
  @DataBoundConstructor
  public CreateOSDiskAttachment(String id, String mountPath) {
    this.id = id;
    this.mountPath = mountPath;
  }

  public String getId() {
    return id;
  }

  public String getMountPath() {
    return mountPath;
  }

  public String getSubPath() {
    return subPath;
  }

  @DataBoundSetter
  public void setSubPath(String subPath) {
    this.subPath = subPath;
  }

  /**
   * Validates paths again at runtime so Pipeline and restored configuration cannot bypass UI
   * checks.
   */
  void validatePaths() {
    String error = mountPathError(mountPath);
    if (error != null) {
      throw new IllegalArgumentException(error);
    }
    error = subPathError(subPath);
    if (error != null) {
      throw new IllegalArgumentException(error);
    }
  }

  private static String mountPathError(String value) {
    if (value == null || value.isBlank()) {
      return "Mount path is required";
    }
    if (!value.startsWith("/")) {
      return "Mount path must be absolute";
    }
    if (value.contains("..")) {
      return "Mount path must not contain '..'";
    }
    return null;
  }

  private static String subPathError(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    if (value.startsWith("/")) {
      return "Sub path must be relative";
    }
    if (value.contains("..")) {
      return "Sub path must not contain '..'";
    }
    return null;
  }

  /** Exposes disk attachment metadata and validation to Jenkins. */
  @Extension
  public static class DescriptorImpl extends Descriptor<CreateOSDiskAttachment> {

    @Override
    public String getDisplayName() {
      return "CreateOS Disk Attachment";
    }

    /** Validates that the mount path is absolute. */
    @POST
    public FormValidation doCheckMountPath(@QueryParameter String value) {
      Jenkins.get().checkPermission(Jenkins.ADMINISTER);
      String error = mountPathError(value);
      return error == null ? FormValidation.ok() : FormValidation.error(error);
    }

    /** Validates that the optional sub-path stays within the disk prefix. */
    @POST
    public FormValidation doCheckSubPath(@QueryParameter String value) {
      Jenkins.get().checkPermission(Jenkins.ADMINISTER);
      String error = subPathError(value);
      return error == null ? FormValidation.ok() : FormValidation.error(error);
    }
  }
}
