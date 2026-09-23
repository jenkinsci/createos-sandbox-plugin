package sh.createos.jenkins.sandbox;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.sun.net.httpserver.HttpServer;
import hudson.util.Secret;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Covers the two decisions that make a controller restart survivable, against a stub CreateOS API.
 *
 * <p>Both answer the same question — does this agent still own a sandbox worth reconnecting to —
 * and both are dangerous when wrong in opposite directions: a false negative destroys a running
 * build's workspace, a false positive attaches an agent to a sandbox that is gone.
 */
@WithJenkins
class CreateOSRecoveryTest {

  /** Sandbox ids the stub API reports as running; anything else 404s. */
  private static final Map<String, String> SANDBOX_STATUS =
      Map.of("sb-alive", "running", "sb-stopped", "stopped");

  private HttpServer server;

  @BeforeEach
  void startStubApi() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/sandboxes/",
        exchange -> {
          String id = exchange.getRequestURI().getPath().substring("/v1/sandboxes/".length());
          String status = SANDBOX_STATUS.get(id);
          byte[] body =
              (status == null
                      ? "{}"
                      : "{\"data\":{\"id\":\"" + id + "\",\"status\":\"" + status + "\"}}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status == null ? 404 : 200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopStubApi() {
    server.stop(0);
  }

  @Test
  void anSshAgentWhoseSandboxSurvivedIsRecovered(JenkinsRule r) throws Exception {
    CreateOSSlave slave = agent(r, sshTemplate(), "sb-alive");

    assertTrue(CreateOSCloud.isRecoverable(slave));
    assertTrue(CreateOSLauncher.adoptsExistingSandbox(slave, cloudOf(slave).buildApiClient()));
  }

  @Test
  void anSshAgentWhoseSandboxIsGoneIsNotRecovered(JenkinsRule r) throws Exception {
    CreateOSSlave slave = agent(r, sshTemplate(), "sb-deleted");

    assertFalse(CreateOSCloud.isRecoverable(slave));
    assertFalse(CreateOSLauncher.adoptsExistingSandbox(slave, cloudOf(slave).buildApiClient()));
  }

  /** A stopped sandbox has no sshd and no tunnel endpoint; only "running" is adoptable. */
  @Test
  void anSshAgentWhoseSandboxStoppedIsNotRecovered(JenkinsRule r) throws Exception {
    CreateOSSlave slave = agent(r, sshTemplate(), "sb-stopped");

    assertFalse(CreateOSCloud.isRecoverable(slave));
    assertFalse(CreateOSLauncher.adoptsExistingSandbox(slave, cloudOf(slave).buildApiClient()));
  }

  /** An agent that never reached the API has nothing to adopt, whatever its sandbox once was. */
  @Test
  void anAgentWithoutASandboxIdIsNotRecovered(JenkinsRule r) throws Exception {
    CreateOSSlave slave = agent(r, sshTemplate(), null);

    assertFalse(CreateOSCloud.isRecoverable(slave));
    assertFalse(CreateOSLauncher.adoptsExistingSandbox(slave, cloudOf(slave).buildApiClient()));
  }

  /**
   * An inbound agent with a live sandbox is kept too: its agent process reconnects the WebSocket by
   * itself once the controller is back. The launcher must adopt that sandbox rather than create a
   * second one — on a live controller the duplicate name was rejected, and the failure path then
   * destroyed the running original.
   */
  @Test
  void anInboundAgentWithALiveSandboxIsRecovered(JenkinsRule r) throws Exception {
    CreateOSSlave slave =
        agent(r, new SandboxTemplate("createos", "s-1vcpu-1gb", "devbox:1"), "sb-alive");

    assertTrue(CreateOSCloud.isRecoverable(slave));
    assertTrue(CreateOSLauncher.adoptsExistingSandbox(slave, cloudOf(slave).buildApiClient()));
  }

  /** The opt-out: a template that asks for deletion on restart is not reconnected. */
  @Test
  void anSshAgentWhoseTemplateDeletesOnRestartIsNotRecovered(JenkinsRule r) throws Exception {
    SandboxTemplate template = sshTemplate();
    template.setDeleteOnRestart(true);
    CreateOSSlave slave = agent(r, template, "sb-alive");

    assertFalse(CreateOSCloud.isRecoverable(slave));
  }

  // --- helpers -------------------------------------------------------------

  private static SandboxTemplate sshTemplate() {
    SandboxTemplate template = new SandboxTemplate("createos", "s-1vcpu-1gb", "devbox:1");
    template.setLauncher(new SshLaunchMethod("createos-ssh-key"));
    return template;
  }

  private static CreateOSCloud cloudOf(CreateOSSlave slave) {
    return slave.getCreateOSCloud();
  }

  private CreateOSSlave agent(JenkinsRule r, SandboxTemplate template, String sandboxId)
      throws Exception {
    SystemCredentialsProvider.getInstance()
        .getCredentials()
        .add(
            new StringCredentialsImpl(
                CredentialsScope.GLOBAL,
                "createos-api-key",
                "CreateOS API key",
                Secret.fromString("sk-test")));

    CreateOSCloud cloud = new CreateOSCloud("createos");
    cloud.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort());
    cloud.setCredentialsId("createos-api-key");
    cloud.setTemplates(List.of(template));
    r.jenkins.clouds.add(cloud);

    CreateOSSlave slave = new CreateOSSlave("createos-test", template, cloud);
    slave.setSandboxId(sandboxId);
    return slave;
  }
}
