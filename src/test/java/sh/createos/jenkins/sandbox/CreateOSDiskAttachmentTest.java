package sh.createos.jenkins.sandbox;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Verifies the path rules shared by form validation and runtime API requests. */
class CreateOSDiskAttachmentTest {

  @Test
  void acceptsAbsoluteMountPathAndRelativeSubPath() {
    CreateOSDiskAttachment disk = new CreateOSDiskAttachment("disk", "/mnt/workspace");
    disk.setSubPath("team/project");

    assertDoesNotThrow(disk::validatePaths);
  }

  @Test
  void acceptsMissingOptionalSubPath() {
    CreateOSDiskAttachment disk = new CreateOSDiskAttachment("disk", "/mnt/workspace");

    assertDoesNotThrow(disk::validatePaths);
  }

  @Test
  void rejectsMissingMountPath() {
    CreateOSDiskAttachment disk = new CreateOSDiskAttachment("disk", " ");

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, disk::validatePaths);

    assertEquals("Mount path is required", error.getMessage());
  }

  @Test
  void rejectsRelativeMountPath() {
    CreateOSDiskAttachment disk = new CreateOSDiskAttachment("disk", "mnt/workspace");

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, disk::validatePaths);

    assertEquals("Mount path must be absolute", error.getMessage());
  }

  @Test
  void rejectsMountPathTraversal() {
    CreateOSDiskAttachment disk = new CreateOSDiskAttachment("disk", "/mnt/data/../../etc");

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, disk::validatePaths);

    assertEquals("Mount path must not contain '..'", error.getMessage());
  }

  @Test
  void rejectsAbsoluteSubPath() {
    CreateOSDiskAttachment disk = new CreateOSDiskAttachment("disk", "/mnt/data");
    disk.setSubPath("/other-tenant");

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, disk::validatePaths);

    assertEquals("Sub path must be relative", error.getMessage());
  }

  @Test
  void rejectsSubPathTraversal() {
    CreateOSDiskAttachment disk = new CreateOSDiskAttachment("disk", "/mnt/data");
    disk.setSubPath("../../other-tenant");

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, disk::validatePaths);

    assertEquals("Sub path must not contain '..'", error.getMessage());
  }
}
