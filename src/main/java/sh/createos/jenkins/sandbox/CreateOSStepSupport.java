package sh.createos.jenkins.sandbox;

import hudson.FilePath;
import hudson.Util;
import hudson.slaves.Cloud;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

/** Shared helpers for CreateOS Pipeline steps. */
final class CreateOSStepSupport {

  private CreateOSStepSupport() {}

  static CreateOSCloud resolveCloud(String cloudName) {
    Jenkins jenkins = Jenkins.get();
    for (Cloud cloud : jenkins.clouds) {
      if (cloud instanceof CreateOSCloud createOSCloud) {
        if (cloudName == null || cloudName.isBlank() || cloud.name.equals(cloudName)) {
          return createOSCloud;
        }
      }
    }
    throw new IllegalStateException(
        cloudName == null || cloudName.isBlank()
            ? "No CreateOS cloud is configured"
            : "CreateOS cloud not found: " + cloudName);
  }

  static CreateOSSandboxRequest requestFromStep(CreateOSSandboxStep step) throws IOException {
    CreateOSCloud cloud = resolveCloud(step.getCloud());

    SandboxTemplate inherited = null;
    if (step.getInheritFrom() != null && !step.getInheritFrom().isBlank()) {
      inherited = cloud.getTemplateByLabel(step.getInheritFrom());
      if (inherited == null) {
        throw new IOException("CreateOS template not found: " + step.getInheritFrom());
      }
    }
    if (inherited != null && hasTemplateOverrides(step)) {
      try {
        inherited.rejectPipelineOverrides();
      } catch (IllegalStateException e) {
        throw new IOException(e.getMessage(), e);
      }
    }

    String shape = firstNonBlank(step.getShape(), inherited == null ? null : inherited.getShape());
    String rootfs =
        firstNonBlank(step.getRootfs(), inherited == null ? null : inherited.getRootfs());
    if (shape == null || rootfs == null) {
      throw new IOException("shape and rootfs are required unless inheritFrom supplies them");
    }

    String region =
        firstNonBlank(step.getRegion(), inherited == null ? null : inherited.getRegion());
    int diskMiB =
        step.getDiskMiB() > 0 ? step.getDiskMiB() : inherited == null ? 0 : inherited.getDiskMiB();
    List<String> networks =
        step.getNetworks() == null || step.getNetworks().isEmpty()
            ? inherited == null ? List.of() : inherited.getNetworkIdList()
            : step.getNetworks();
    List<CreateOSDiskAttachment> disks =
        step.getDisks() == null || step.getDisks().isEmpty()
            ? inherited == null ? List.of() : inherited.getDisks()
            : step.getDisks();

    // Deliberately unnamed: an exec-mode sandbox backs a pipeline block, not an agent, so it has
    // no node for CreateOSSandboxSweep to match it against. Naming it the way agent sandboxes are
    // named would make it look orphaned and get it destroyed underneath a running build.
    return new CreateOSSandboxRequest(null, shape, rootfs, region, diskMiB, networks, disks);
  }

  static boolean hasTemplateOverrides(CreateOSTemplateOverrides overrides) {
    return firstNonBlank(overrides.getShape(), null) != null
        || firstNonBlank(overrides.getRootfs(), null) != null
        || firstNonBlank(overrides.getRegion(), null) != null
        || firstNonBlank(overrides.getRemoteFs(), null) != null
        || overrides.getDiskMiB() > 0
        || (overrides.getNetworks() != null && !overrides.getNetworks().isEmpty())
        || (overrides.getDisks() != null && !overrides.getDisks().isEmpty());
  }

  static String firstNonBlank(String first, String fallback) {
    if (first != null && !first.isBlank()) {
      return first;
    }
    if (fallback != null && !fallback.isBlank()) {
      return fallback;
    }
    return null;
  }

  static String joinRemotePath(String base, String relative) {
    String normalizedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    String normalizedRelative = relative.startsWith("/") ? relative.substring(1) : relative;
    return normalizedBase + "/" + normalizedRelative;
  }

  static FilePath workspacePath(FilePath workspace, String path)
      throws IOException, InterruptedException {
    if (path == null
        || path.isBlank()
        || !Util.isRelativePath(path)
        || !workspace.isDescendant(path)) {
      throw new IllegalArgumentException("Path must stay inside the workspace: " + path);
    }
    return workspace.child(path);
  }

  static void parseListField(JSONObject form, String field) {
    if (form.opt(field) instanceof String text) {
      form.put(
          field,
          Arrays.stream(text.split("[,\\r\\n]"))
              .map(String::trim)
              .filter(value -> !value.isEmpty())
              .toList());
    }
  }

  static void requireAbsoluteRemotePath(String path, String fieldName) {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    if (!path.startsWith("/")) {
      throw new IllegalArgumentException(fieldName + " must be an absolute sandbox path");
    }
    if (path.contains("..")) {
      throw new IllegalArgumentException(fieldName + " must not contain '..'");
    }
  }
}
