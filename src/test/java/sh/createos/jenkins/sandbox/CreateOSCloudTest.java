package sh.createos.jenkins.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.sun.net.httpserver.HttpServer;
import hudson.model.Label;
import hudson.slaves.Cloud.CloudState;
import hudson.util.FormValidation;
import hudson.util.Secret;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class CreateOSCloudTest {

  private static CreateOSCloud cloudWithTemplate(String label) {
    CreateOSCloud cloud = new CreateOSCloud("createos");
    cloud.setTemplates(List.of(new SandboxTemplate(label, "s-1vcpu-1gb", "devbox:1")));
    return cloud;
  }

  @Test
  void canProvisionMatchesOnlyItsOwnLabel(JenkinsRule r) {
    CreateOSCloud cloud = cloudWithTemplate("createos");

    assertTrue(cloud.canProvision(new CloudState(Label.get("createos"), 0)));
    assertFalse(cloud.canProvision(new CloudState(Label.get("some-other-label"), 0)));
    // `agent any` reaches the cloud with a null Label. CreateOSSlave is EXCLUSIVE, so a sandbox
    // provisioned for it could never accept the job — claiming it would leak one sandbox per
    // provisioner cycle on a controller with zero executors.
    assertFalse(cloud.canProvision(new CloudState(null, 0)));
  }

  /** An unlabelled job cannot run on an exclusive CreateOS agent. */
  @Test
  void unlabelledWorkIsNotProvisioned(JenkinsRule r) {
    CreateOSCloud cloud = cloudWithTemplate("createos");

    CloudState state = new CloudState(null, 0);
    assertTrue(cloud.provision(state, 1).isEmpty(), "no sandbox for an unlabelled job");
  }

  @Test
  void provisionUsesJenkinsExcessWorkload(JenkinsRule r) {
    CreateOSCloud cloud = cloudWithTemplate("createos");
    cloud.setContainerCap(1);

    assertEquals(1, cloud.provision(new CloudState(Label.get("createos"), 0), 1).size());
  }

  @Test
  void containerCapDoesNotCountAgentsFromAnotherCloud(JenkinsRule r) throws Exception {
    CreateOSCloud first = cloudWithTemplate("first");
    CreateOSCloud second = new CreateOSCloud("second");
    second.setContainerCap(1);
    second.setTemplates(List.of(new SandboxTemplate("second", "s-1vcpu-1gb", "devbox:1")));
    r.jenkins.addNode(new CreateOSSlave("first-agent", first.getTemplates().get(0), first));

    assertEquals(1, second.provision(new CloudState(Label.get("second"), 0), 1).size());
  }

  @Test
  void testConnectionCallsCreateOsWithConfiguredKey(JenkinsRule r) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/sandboxes",
        exchange -> {
          boolean authorized = "sk-test".equals(exchange.getRequestHeaders().getFirst("x-api-key"));
          byte[] body =
              (authorized ? "{\"data\":[]}" : "unauthorized").getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(authorized ? 200 : 401, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      SystemCredentialsProvider.getInstance()
          .getCredentials()
          .add(
              new StringCredentialsImpl(
                  CredentialsScope.GLOBAL, "createos-test", "Test", Secret.fromString("sk-test")));
      SystemCredentialsProvider.getInstance()
          .getCredentials()
          .add(
              new StringCredentialsImpl(
                  CredentialsScope.GLOBAL, "createos-wrong", "Wrong", Secret.fromString("bad")));
      CreateOSCloud.DescriptorImpl descriptor =
          r.jenkins.getDescriptorByType(CreateOSCloud.DescriptorImpl.class);
      String url = "http://127.0.0.1:" + server.getAddress().getPort();

      assertEquals(FormValidation.Kind.OK, descriptor.doTestConnection(url, "createos-test").kind);
      assertEquals(
          FormValidation.Kind.ERROR, descriptor.doTestConnection(url, "createos-wrong").kind);
      assertEquals(FormValidation.Kind.ERROR, descriptor.doTestConnection(url, "missing").kind);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void declarativeOverridesAreRejectedWhenTemplateDisablesThem(JenkinsRule r) {
    SandboxTemplate template = new SandboxTemplate("createos", "s-1vcpu-1gb", "devbox:1");
    template.setAllowPipelineOverrides(false);
    CreateOSCloud cloud = new CreateOSCloud("createos");
    cloud.setTemplates(List.of(template));
    r.jenkins.clouds.add(cloud);

    CreateOSDeclarativeAgent agent = new CreateOSDeclarativeAgent("createos");
    agent.setShape("s-8vcpu-8gb");

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> CreateOSPipelineSupport.register(agent, "createos-generated"));

    assertEquals(
        "CreateOS Pipeline-defined overrides are disabled for template: createos",
        thrown.getMessage());
    assertNull(cloud.getTemplateByLabel("createos-generated"));
    assertFalse(cloud.canProvision(new CloudState(Label.get("createos-generated"), 0)));
  }

  @Test
  void declarativeAgentWithoutAdminTemplateIsRejected(JenkinsRule r) {
    CreateOSCloud cloud = cloudWithTemplate("createos");
    r.jenkins.clouds.add(cloud);
    CreateOSDeclarativeAgent agent = new CreateOSDeclarativeAgent(null);
    agent.setShape("s-8vcpu-8gb");
    agent.setRootfs("devbox:1");

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> CreateOSPipelineSupport.register(agent, "createos-generated"));

    assertEquals(
        "CreateOS Declarative agents must inherit from an administrator-defined template",
        thrown.getMessage());
    assertNull(cloud.getTemplateByLabel("createos-generated"));
    assertFalse(cloud.canProvision(new CloudState(Label.get("createos-generated"), 0)));
  }

  @Test
  void declarativeInheritanceWithoutOverridesStillWorksWhenTemplateDisablesOverrides(
      JenkinsRule r) {
    SandboxTemplate template = new SandboxTemplate("createos", "s-1vcpu-1gb", "devbox:1");
    template.setAllowPipelineOverrides(false);
    CreateOSCloud cloud = new CreateOSCloud("createos");
    cloud.setTemplates(List.of(template));
    r.jenkins.clouds.add(cloud);

    CreateOSDeclarativeAgent agent = new CreateOSDeclarativeAgent("createos");

    String label = CreateOSPipelineSupport.register(agent, "createos-generated");

    assertEquals("createos-generated", label);
    assertTrue(cloud.canProvision(new CloudState(Label.get("createos-generated"), 0)));
  }

  @Test
  void scriptedSandboxOverridesAreRejectedWhenTemplateDisablesThem(JenkinsRule r) {
    SandboxTemplate template = new SandboxTemplate("createos", "s-1vcpu-1gb", "devbox:1");
    template.setAllowPipelineOverrides(false);
    CreateOSCloud cloud = new CreateOSCloud("createos");
    cloud.setTemplates(List.of(template));
    r.jenkins.clouds.add(cloud);

    CreateOSSandboxStep step = new CreateOSSandboxStep();
    step.setInheritFrom("createos");
    step.setShape("s-8vcpu-8gb");

    IOException thrown =
        assertThrows(IOException.class, () -> CreateOSStepSupport.requestFromStep(step));

    assertEquals(
        "CreateOS Pipeline-defined overrides are disabled for template: createos",
        thrown.getMessage());
  }

  @Test
  void scriptedSandboxCanUseAdminTemplateWithoutOverridesWhenTemplateDisablesOverrides(
      JenkinsRule r) throws Exception {
    SandboxTemplate template = new SandboxTemplate("createos", "s-1vcpu-1gb", "devbox:1");
    template.setAllowPipelineOverrides(false);
    CreateOSCloud cloud = new CreateOSCloud("createos");
    cloud.setTemplates(List.of(template));
    r.jenkins.clouds.add(cloud);

    CreateOSSandboxStep step = new CreateOSSandboxStep();
    step.setInheritFrom("createos");

    CreateOSSandboxRequest request = CreateOSStepSupport.requestFromStep(step);

    assertEquals("s-1vcpu-1gb", request.shape());
    assertEquals("devbox:1", request.rootfs());
  }
}
