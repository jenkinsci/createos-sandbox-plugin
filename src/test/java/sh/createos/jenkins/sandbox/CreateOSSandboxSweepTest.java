package sh.createos.jenkins.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import sh.createos.jenkins.sandbox.CreateOSApiClient.SandboxSummary;

/**
 * The sweep is the safety net that lets a restart reconnect agents instead of destroying them, so
 * both of its mistakes are expensive: keeping an orphan bills forever, and reclaiming a live one
 * kills a running build. Needs a real Jenkins because the sandbox name prefix is derived from the
 * controller's instance identity.
 */
@WithJenkins
class CreateOSSandboxSweepTest {

  private static SandboxSummary ours(String agentName) {
    return new SandboxSummary("sb-" + agentName, CreateOSSlave.sandboxName(agentName), "running");
  }

  @Test
  void reclaimsSandboxWhoseAgentIsGone(JenkinsRule r) {
    List<SandboxSummary> orphans =
        CreateOSSandboxSweep.reclaimable(List.of(ours("lost")), Set.of(), name -> false);

    assertEquals(1, orphans.size());
    assertEquals("sb-lost", orphans.get(0).id());
  }

  @Test
  void keepsSandboxThatStillHasNode(JenkinsRule r) {
    assertTrue(
        CreateOSSandboxSweep.reclaimable(
                List.of(ours("live")), Set.of(), name -> name.equals("live"))
            .isEmpty());
  }

  /**
   * The window between creating a sandbox and registering its node must not look like an orphan.
   */
  @Test
  void keepsSandboxStillBeingProvisioned(JenkinsRule r) {
    assertTrue(
        CreateOSSandboxSweep.reclaimable(List.of(ours("planned")), Set.of("planned"), name -> false)
            .isEmpty());
  }

  /**
   * Another Jenkins on the same account, or a human's own box, is not this controller's to kill.
   */
  @Test
  void ignoresSandboxesThisControllerDidNotName(JenkinsRule r) {
    List<SandboxSummary> foreign =
        List.of(
            new SandboxSummary("sb-other", "jenkins-deadbeef-agent-1", "running"),
            new SandboxSummary("sb-human", "my-dev-box", "running"));

    assertTrue(CreateOSSandboxSweep.reclaimable(foreign, Set.of(), name -> false).isEmpty());
  }
}
