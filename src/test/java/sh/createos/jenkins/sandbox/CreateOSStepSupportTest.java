package sh.createos.jenkins.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import hudson.FilePath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.sf.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CreateOSStepSupportTest {

  @Test
  void workspacePathRejectsEscapesAndSymlinks(@TempDir Path root) throws Exception {
    FilePath workspace = new FilePath(root.toFile());
    Files.createSymbolicLink(root.resolve("outside"), root.getParent());

    assertEquals(
        root.resolve("output.txt").toString(),
        CreateOSStepSupport.workspacePath(workspace, "output.txt").getRemote());
    assertThrows(
        IllegalArgumentException.class,
        () -> CreateOSStepSupport.workspacePath(workspace, "../outside.txt"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CreateOSStepSupport.workspacePath(workspace, root.resolve("absolute.txt").toString()));
    assertThrows(
        IllegalArgumentException.class,
        () -> CreateOSStepSupport.workspacePath(workspace, "outside/file.txt"));
  }

  @Test
  void textareaListFieldsBecomeArraysWithoutChangingPipelineLists() {
    JSONObject form = new JSONObject();
    form.put("networks", "first, second\nthird");
    CreateOSStepSupport.parseListField(form, "networks");
    assertEquals(List.of("first", "second", "third"), form.getJSONArray("networks"));

    JSONObject pipelineForm = new JSONObject();
    pipelineForm.put("networks", List.of("from-pipeline"));
    CreateOSStepSupport.parseListField(pipelineForm, "networks");
    assertEquals(List.of("from-pipeline"), pipelineForm.getJSONArray("networks"));
  }
}
