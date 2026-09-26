package sh.createos.jenkins.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hudson.model.Result;
import hudson.util.Secret;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class CreateOSSandboxStepTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void failedStartupDestroysSandboxAndReleasesReservation(JenkinsRule r) throws Exception {
    try (SandboxApiStub api = new SandboxApiStub("error")) {
      final CreateOSCloud cloud = configureCloud(r, api, 1);

      WorkflowRun run = pipeline(r, "failed-startup").scheduleBuild2(0).get();

      assertEquals(Result.FAILURE, run.getResult());
      r.assertLogContains("Destroying sandbox after failed startup: sb-test", run);
      assertEquals(1, api.creates.get());
      assertEquals(1, api.deletes.get());
      assertNotNull(api.createdName.get());
      assertTrue(CreateOSSlave.namedByThisController(api.createdName.get()));
      assertTrue(cloud.reserveExecSandbox("after-failure"), "startup released its capacity");
      cloud.releaseExecSandbox("after-failure");
    }
  }

  @Test
  void successfulBodyDestroysSandboxAndReleasesReservation(JenkinsRule r) throws Exception {
    try (SandboxApiStub api = new SandboxApiStub("running")) {
      final CreateOSCloud cloud = configureCloud(r, api, 1);

      WorkflowRun run = r.buildAndAssertSuccess(pipeline(r, "successful-exec"));

      r.assertLogContains("Destroying CreateOS sandbox: sb-test", run);
      assertEquals(1, api.creates.get());
      assertEquals(1, api.deletes.get());
      assertTrue(cloud.reserveExecSandbox("after-success"), "cleanup released its capacity");
      cloud.releaseExecSandbox("after-success");
    }
  }

  @Test
  void execSandboxCapRejectsBeforeApiCall(JenkinsRule r) throws Exception {
    try (SandboxApiStub api = new SandboxApiStub("running")) {
      CreateOSCloud cloud = configureCloud(r, api, 1);
      assertTrue(cloud.reserveExecSandbox("already-running"));

      WorkflowRun run = pipeline(r, "cap-reached").scheduleBuild2(0).get();

      assertEquals(Result.FAILURE, run.getResult());
      r.assertLogContains("CreateOS exec sandbox cap reached for cloud 'createos' (1)", run);
      assertEquals(0, api.creates.get());
      cloud.releaseExecSandbox("already-running");
    }
  }

  private static WorkflowJob pipeline(JenkinsRule r, String name) throws Exception {
    WorkflowJob job = r.createProject(WorkflowJob.class, name);
    job.setDefinition(new CpsFlowDefinition("createosSandbox(inheritFrom: 'createos') {}", true));
    return job;
  }

  private static CreateOSCloud configureCloud(JenkinsRule r, SandboxApiStub api, int execCap) {
    SystemCredentialsProvider.getInstance()
        .getCredentials()
        .add(
            new StringCredentialsImpl(
                CredentialsScope.GLOBAL,
                "createos-step-test",
                "Test",
                Secret.fromString("test-key")));
    CreateOSCloud cloud = new CreateOSCloud("createos");
    cloud.setApiUrl(api.url());
    cloud.setCredentialsId("createos-step-test");
    cloud.setExecSandboxCap(execCap);
    cloud.setTemplates(List.of(new SandboxTemplate("createos", "s-1vcpu-1gb", "devbox:1")));
    r.jenkins.clouds.add(cloud);
    return cloud;
  }

  private static final class SandboxApiStub implements AutoCloseable {

    private final HttpServer server;
    private final String sandboxStatus;
    private final AtomicInteger creates = new AtomicInteger();
    private final AtomicInteger deletes = new AtomicInteger();
    private final AtomicReference<String> createdName = new AtomicReference<>();

    SandboxApiStub(String sandboxStatus) throws IOException {
      this.sandboxStatus = sandboxStatus;
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/v1/sandboxes", this::handle);
      server.start();
    }

    String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
      String path = exchange.getRequestURI().getPath();
      if ("POST".equals(exchange.getRequestMethod()) && "/v1/sandboxes".equals(path)) {
        JsonNode request = MAPPER.readTree(exchange.getRequestBody());
        createdName.set(request.path("name").asText(null));
        creates.incrementAndGet();
        respond(exchange, 201, "{\"data\":{\"id\":\"sb-test\"}}");
        return;
      }
      if ("GET".equals(exchange.getRequestMethod()) && "/v1/sandboxes/sb-test".equals(path)) {
        respond(
            exchange, 200, "{\"data\":{\"id\":\"sb-test\",\"status\":\"" + sandboxStatus + "\"}}");
        return;
      }
      if ("DELETE".equals(exchange.getRequestMethod()) && "/v1/sandboxes/sb-test".equals(path)) {
        deletes.incrementAndGet();
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
        return;
      }
      respond(exchange, 404, "{\"data\":{}}");
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
      byte[] body = json.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
