package sh.createos.jenkins.sandbox;

import java.util.List;

/** Immutable inputs for a CreateOS sandbox create request. */
public record CreateOSSandboxRequest(
    String name,
    String shape,
    String rootfs,
    String region,
    int diskMiB,
    List<String> networkIds,
    List<CreateOSDiskAttachment> disks) {

  /** Copies the collections so a caller cannot mutate a request after it is built. */
  public CreateOSSandboxRequest {
    networkIds = networkIds == null ? List.of() : List.copyOf(networkIds);
    disks = disks == null ? List.of() : List.copyOf(disks);
  }

  /**
   * Builds a sandbox create request that names the sandbox after the agent it will back.
   *
   * <p>The name is what lets the controller recognize its own sandboxes later: an agent whose node
   * disappeared leaves a sandbox nobody would otherwise be able to attribute, and billing continues
   * until someone notices.
   */
  public static CreateOSSandboxRequest fromTemplate(SandboxTemplate template, String name) {
    return new CreateOSSandboxRequest(
        name,
        template.getShape(),
        template.getRootfs(),
        template.getRegion(),
        template.getDiskMiB(),
        template.getNetworkIdList(),
        template.getDisks());
  }
}
