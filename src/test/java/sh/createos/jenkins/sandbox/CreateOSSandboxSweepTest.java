package sh.createos.jenkins.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

  /**
   * The cap the CreateOS API enforces. An over-long name is rejected with HTTP 400, which fails
   * every agent launch — a whole-plugin outage that only an end-to-end run would otherwise catch.
   */
  @Test
  void sandboxNameFitsWithinTheApiLimit(JenkinsRule r) {
    String name = CreateOSSlave.sandboxName("createos-createos-restart-1790093510215-0");

    assertTrue(name.length() <= 22, "sandbox name is " + name.length() + " chars: " + name);
    assertTrue(name.matches("[a-z0-9-]+"), name);
  }

  @Test
  void sandboxNamesDistinguishAgentsAndAreStable(JenkinsRule r) {
    assertEquals(CreateOSSlave.sandboxName("agent-a"), CreateOSSlave.sandboxName("agent-a"));
    assertFalse(CreateOSSlave.sandboxName("agent-a").equals(CreateOSSlave.sandboxName("agent-b")));
  }

  @Test
  void reclaimsSandboxWhoseAgentIsGone(JenkinsRule r) {
    List<SandboxSummary> orphans =
        CreateOSSandboxSweep.reclaimable(List.of(ours("lost")), Set.of(), Set.of());

    assertEquals(1, orphans.size());
    assertEquals("sb-lost", orphans.get(0).id());
  }

  /** The link is the sandbox id the node persists, not the name, which cannot carry an agent. */
  @Test
  void keepsSandboxThatStillHasNode(JenkinsRule r) {
    assertTrue(
        CreateOSSandboxSweep.reclaimable(List.of(ours("live")), Set.of("sb-live"), Set.of())
            .isEmpty());
  }

  /**
   * The window between creating a sandbox and registering its node must not look like an orphan.
   * The id is not known yet, so the in-flight agent is recognised by the name it will be given.
   */
  @Test
  void keepsSandboxStillBeingProvisioned(JenkinsRule r) {
    assertTrue(
        CreateOSSandboxSweep.reclaimable(
                List.of(ours("planned")), Set.of(), Set.of(CreateOSSlave.sandboxName("planned")))
            .isEmpty());
  }

  @Test
  void keepsActiveExecSandbox(JenkinsRule r) {
    CreateOSCloud cloud = new CreateOSCloud("createos");
    String sandboxName = CreateOSSlave.sandboxName("exec-active");
    assertTrue(cloud.reserveExecSandbox(sandboxName));
    SandboxSummary sandbox = new SandboxSummary("sb-exec", sandboxName, "running");

    assertTrue(
        CreateOSSandboxSweep.reclaimable(List.of(sandbox), Set.of(), cloud.activeExecSandboxNames())
            .isEmpty());
  }

  /**
   * Another Jenkins on the same account, or a human's own box, is not this controller's to kill.
   */
  @Test
  void ignoresSandboxesThisControllerDidNotName(JenkinsRule r) {
    List<SandboxSummary> foreign =
        List.of(
            new SandboxSummary("sb-other", "jk-deadbe-agent1", "running"),
            new SandboxSummary("sb-human", "my-dev-box", "running"));

    assertTrue(CreateOSSandboxSweep.reclaimable(foreign, Set.of(), Set.of()).isEmpty());
  }
}
