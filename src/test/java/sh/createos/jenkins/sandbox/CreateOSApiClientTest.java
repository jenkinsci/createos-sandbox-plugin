package sh.createos.jenkins.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Verifies that API keys are redacted without discarding control-plane diagnostics. */
class CreateOSApiClientTest {

  private static final String API_KEY = "skp_testKey_123-abc";
  private static final String DIAGNOSTIC = "validation detail agent-connect-secret";

  private HttpServer server;
  private CreateOSApiClient client;
  private volatile int responseStatus = 422;
  private volatile String responseBody = DIAGNOSTIC + " " + API_KEY;

  @BeforeEach
  void startStubApi() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(responseStatus, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    client = new CreateOSApiClient("http://127.0.0.1:" + server.getAddress().getPort(), "api-key");
  }

  @AfterEach
  void stopStubApi() {
    server.stop(0);
  }

  @Test
  void ordinaryApiErrorsRetainDiagnosticsButRedactApiKeys() {
    IOException error = assertThrows(IOException.class, client::testConnection);

    assertEquals(
        "CreateOS API error: HTTP 422 — " + DIAGNOSTIC + " skp_[REDACTED]", error.getMessage());
    assertFalse(error.getMessage().contains(API_KEY));
  }

  @Test
  void malformedSuccessResponseDiagnosticsRedactApiKeys() {
    responseStatus = 200;
    responseBody = API_KEY;

    IOException error = assertThrows(IOException.class, client::testConnection);

    assertFalse(error.getMessage().contains(API_KEY));
    assertTrue(error.getMessage().contains("skp_[REDACTED]"));
  }

  @Test
  void execHttpErrorsRetainDiagnosticsButRedactApiKeys() {
    ByteArrayOutputStream log = new ByteArrayOutputStream();

    IOException error =
        assertThrows(
            IOException.class,
            () -> client.runShellScript("sandbox", "echo harmless", new PrintStream(log), false));

    assertEquals(
        "CreateOS exec error: HTTP 422 — " + DIAGNOSTIC + " skp_[REDACTED]", error.getMessage());
    assertFalse(error.getMessage().contains(API_KEY));
    assertFalse(log.toString(StandardCharsets.UTF_8).contains(API_KEY));
  }

  @Test
  void execStreamErrorsRetainDiagnosticsButRedactApiKeys() throws IOException {
    responseStatus = 200;
    responseBody = "{\"error\":\"" + DIAGNOSTIC + " " + API_KEY + "\"}\n{\"exit_code\":0}\n";
    ByteArrayOutputStream log = new ByteArrayOutputStream();

    CreateOSApiClient.ExecResult result =
        client.runShellScript("sandbox", "echo harmless", new PrintStream(log), false);

    assertEquals(0, result.exitCode());
    assertEquals(DIAGNOSTIC + " skp_[REDACTED]\n", log.toString(StandardCharsets.UTF_8));
    assertFalse(log.toString(StandardCharsets.UTF_8).contains(API_KEY));
  }

  @Test
  void fileTransferErrorsRetainDiagnosticsButRedactApiKeys() {
    IOException upload =
        assertThrows(
            IOException.class,
            () ->
                client.uploadFile(
                    "sandbox", "/workspace/input", new ByteArrayInputStream(new byte[] {1, 2, 3})));
    IOException download =
        assertThrows(
            IOException.class,
            () -> client.downloadFile("sandbox", "/workspace/output", new ByteArrayOutputStream()));

    assertEquals(
        "Failed to upload file to /workspace/input: HTTP 422 — " + DIAGNOSTIC + " skp_[REDACTED]",
        upload.getMessage());
    assertEquals(
        "Failed to download file /workspace/output: HTTP 422 — " + DIAGNOSTIC + " skp_[REDACTED]",
        download.getMessage());
    assertFalse(upload.getMessage().contains(API_KEY));
    assertFalse(download.getMessage().contains(API_KEY));
  }

  @Test
  void redactionChangesOnlyApiKeyShapedValues() {
    assertEquals(
        DIAGNOSTIC + " skp_[REDACTED] remains visible",
        CreateOSApiClient.redactApiKeys(DIAGNOSTIC + " " + API_KEY + " remains visible"));
  }
}
