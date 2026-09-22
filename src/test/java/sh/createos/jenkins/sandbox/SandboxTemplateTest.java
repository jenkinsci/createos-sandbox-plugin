package sh.createos.jenkins.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

class SandboxTemplateTest {

  private static SandboxTemplate withNetworks(String networks) {
    SandboxTemplate template = new SandboxTemplate("createos", "s-1vcpu-1gb", "devbox:1");
    template.setNetworks(networks);
    return template;
  }

  @Test
  void networksAreSplitOnBothCommasAndWhitespace() {
    assertEquals(List.of("a", "b", "c"), withNetworks("a, b  c").getNetworkIdList());
  }

  @Test
  void networksDropDuplicatesAndSurroundingBlanks() {
    assertEquals(List.of("a", "b"), withNetworks("  a , b ,, a  ").getNetworkIdList());
  }

  @Test
  void noNetworksMeansAnEmptyListRatherThanAnEmptyEntry() {
    assertTrue(withNetworks("   ").getNetworkIdList().isEmpty());
    assertTrue(withNetworks(null).getNetworkIdList().isEmpty());
  }

  @Test
  void launchMethodDefaultsToInboundForExistingTemplates() {
    SandboxTemplate template = new SandboxTemplate("createos", "s-1vcpu-1gb", "devbox:1");

    assertInstanceOf(InboundLaunchMethod.class, template.getLauncher());
    assertNull(template.sshLauncher());
  }

  @Test
  void pipelineOverridesStayAllowedByDefaultForExistingTemplates() {
    SandboxTemplate template = new SandboxTemplate("createos", "s-1vcpu-1gb", "devbox:1");

    assertTrue(template.isAllowPipelineOverrides());
  }

  /**
   * Templates written before the launch method became describable carry a string and two SSH
   * fields. Reading one must produce the matching transport rather than silently fall back to
   * inbound, which would leave an SSH-only rootfs unreachable.
   */
  @Test
  @WithJenkins
  void anOldSshTemplateMigratesToTheSshLaunchMethod(JenkinsRule r) throws Exception {
    SandboxTemplate migrated =
        (SandboxTemplate)
            Jenkins.XSTREAM2.fromXML(
                """
                <sh.createos.jenkins.sandbox.SandboxTemplate>
                  <label>createos</label>
                  <shape>s-1vcpu-1gb</shape>
                  <rootfs>devbox:1</rootfs>
                  <launchMethod>ssh</launchMethod>
                  <sshCredentialsId>sandbox-key</sshCredentialsId>
                  <sshPublicKey>ssh-ed25519 AAAATEST createos</sshPublicKey>
                </sh.createos.jenkins.sandbox.SandboxTemplate>
                """);

    assertNotNull(migrated.sshLauncher());
    assertEquals("sandbox-key", migrated.sshLauncher().getCredentialsId());
  }

  @Test
  @WithJenkins
  void anOldInboundTemplateMigratesToTheInboundLaunchMethod(JenkinsRule r) {
    SandboxTemplate migrated =
        (SandboxTemplate)
            Jenkins.XSTREAM2.fromXML(
                """
                <sh.createos.jenkins.sandbox.SandboxTemplate>
                  <label>createos</label>
                  <shape>s-1vcpu-1gb</shape>
                  <rootfs>devbox:1</rootfs>
                  <launchMethod>inbound</launchMethod>
                </sh.createos.jenkins.sandbox.SandboxTemplate>
                """);

    assertInstanceOf(InboundLaunchMethod.class, migrated.getLauncher());
  }

  /**
   * A template saved before these settings existed must reconnect across restarts and use the
   * default idle timeout, rather than read a missing field as 0 minutes and reap agents on sight.
   */
  @Test
  @WithJenkins
  void anOldTemplateReconnectsOnRestartWithTheDefaultIdleTimeout(JenkinsRule r) {
    SandboxTemplate migrated =
        (SandboxTemplate)
            Jenkins.XSTREAM2.fromXML(
                """
                <sh.createos.jenkins.sandbox.SandboxTemplate>
                  <label>createos</label>
                  <shape>s-1vcpu-1gb</shape>
                  <rootfs>devbox:1</rootfs>
                </sh.createos.jenkins.sandbox.SandboxTemplate>
                """);

    assertEquals(SandboxTemplate.DEFAULT_IDLE_MINUTES, migrated.getIdleMinutes());
    assertFalse(migrated.isDeleteOnRestart());
  }

  /** Declarative agents inherit these from the admin template; a Jenkinsfile cannot change them. */
  @Test
  void pipelineCopiesKeepTheRestartAndIdlePolicy() {
    SandboxTemplate template = new SandboxTemplate("createos", "s-1vcpu-1gb", "devbox:1");
    template.setIdleMinutes(7);
    template.setDeleteOnRestart(true);

    SandboxTemplate copy = template.copyForPipeline("generated", "s-2vcpu-2gb", "devbox:1");

    assertEquals(7, copy.getIdleMinutes());
    assertTrue(copy.isDeleteOnRestart());
  }
}
