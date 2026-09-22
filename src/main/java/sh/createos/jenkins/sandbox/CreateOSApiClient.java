package sh.createos.jenkins.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hudson.ProxyConfiguration;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * HTTP client for the CreateOS Sandbox control plane API.
 *
 * <p>Uses Java's built-in HttpClient (no external dependencies). All responses follow JSend format:
 * { "status": "success", "data": {...} }
 */
public class CreateOSApiClient {

  private static final Logger LOGGER = Logger.getLogger(CreateOSApiClient.class.getName());
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String baseUrl;
  // lgtm[jenkins/plaintext-storage] This client is transient and never persisted in Jenkins XML.
  private final String apiKey;
  private final HttpClient httpClient;

  /** Creates a client for the given CreateOS API endpoint and API key. */
  public CreateOSApiClient(String baseUrl, String apiKey) {
    this.baseUrl = baseUrl.replaceAll("/+$", "");
    this.apiKey = apiKey;
    this.httpClient =
        ProxyConfiguration.newHttpClientBuilder().connectTimeout(Duration.ofSeconds(30)).build();
  }

  String baseUrl() {
    return baseUrl;
  }

  String apiKey() {
    return apiKey;
  }

  /** Verify that this API key can access the CreateOS sandbox endpoint. */
  public void testConnection() throws IOException {
    get("/v1/sandboxes");
  }

  /**
   * Create a new sandbox and return its ID.
   *
   * <p>POST /v1/sandboxes
   */
  public String createSandbox(CreateOSSandboxRequest request) throws IOException {
    validateNetworks(request);

    ObjectNode body = MAPPER.createObjectNode();
    body.put("shape", request.shape());
    body.put("rootfs", request.rootfs());

    if (request.name() != null && !request.name().isBlank()) {
      body.put("name", request.name());
    }

    if (request.region() != null && !request.region().isBlank()) {
      body.put("region", request.region().trim());
    }

    if (request.diskMiB() > 0) {
      body.put("disk_mib", request.diskMiB());
    }

    if (!request.networkIds().isEmpty()) {
      var networksNode = body.putArray("networks");
      for (String networkId : request.networkIds()) {
        networksNode.addObject().put("id", networkId);
      }
    }

    if (!request.disks().isEmpty()) {
      var disksNode = body.putArray("disks");
      for (CreateOSDiskAttachment disk : request.disks()) {
        ObjectNode diskNode = disksNode.addObject();
        diskNode.put("disk_id", resolveDiskId(disk.getId()));
        diskNode.put("mount_path", disk.getMountPath());
        if (disk.getSubPath() != null && !disk.getSubPath().isBlank()) {
          diskNode.put("sub_path", disk.getSubPath());
        }
      }
    }

    JsonNode data = post("/v1/sandboxes", body);
    String sandboxId = data.get("id").asText();
    LOGGER.fine("Created sandbox: " + sandboxId);
    return sandboxId;
  }

  /**
   * Turns a disk name into its canonical id, because only the id attaches.
   *
   * <p>GET /v1/disks/{idOrName} accepts either, so a name validates happily and reads like a
   * working reference. POST /v1/sandboxes does not: given a name in disk_id it returns 201 and
   * simply creates the sandbox with no disk. Nothing fails, nothing is logged, and the mount is
   * discovered missing later by whatever tries to use it — `mountpoint` exiting 32 against an empty
   * directory. Networks do not behave this way; a network name works end to end.
   *
   * <p>This is also what fails a sandbox whose disk does not exist at all, which is why there is no
   * separate disk pre-check: the GET here is the same call such a check would make.
   *
   * <p>Fails closed when the response carries no id. Sending the name instead would be the exact
   * silent drop this method exists to prevent — a sandbox created without its disk and nobody told.
   * An API shape change should break the launch loudly rather than produce agents whose mounts are
   * quietly absent.
   */
  private String resolveDiskId(String idOrName) throws IOException {
    JsonNode disk;
    try {
      disk = getDisk(idOrName);
    } catch (IOException e) {
      throw new IOException("CreateOS disk not found or not accessible: " + idOrName, e);
    }
    JsonNode id = disk.path("id");
    if (id.isMissingNode() || id.asText().isEmpty()) {
      throw new IOException(
          "CreateOS disk '"
              + idOrName
              + "' resolved to a response with no id; refusing to create a sandbox that would"
              + " silently have no disk attached");
    }
    return id.asText();
  }

  /** Fails fast on missing networks before sandbox creation. */
  private void validateNetworks(CreateOSSandboxRequest request) throws IOException {
    for (String networkId : request.networkIds()) {
      try {
        getNetwork(networkId);
      } catch (IOException e) {
        throw new IOException("CreateOS network not found or not accessible: " + networkId, e);
      }
    }
  }

  /**
   * Get network details.
   *
   * <p>GET /v1/networks/{idOrName}
   */
  public JsonNode getNetwork(String idOrName) throws IOException {
    return get("/v1/networks/" + encode(idOrName));
  }

  /**
   * Get disk details.
   *
   * <p>GET /v1/disks/{idOrName}
   */
  public JsonNode getDisk(String idOrName) throws IOException {
    return get("/v1/disks/" + encode(idOrName));
  }

  /** Get the current status of a sandbox. */
  public String getSandboxStatus(String sandboxId) throws IOException {
    return get("/v1/sandboxes/" + sandboxId).get("status").asText();
  }

  /**
   * Reports whether a sandbox is still running.
   *
   * <p>Any failure to answer counts as "not running". This backs decisions about adopting a sandbox
   * the controller believes it owns, and treating an unreachable or deleted sandbox as alive would
   * attach an agent to nothing.
   */
  public boolean isRunning(String sandboxId) {
    try {
      return "running".equals(getSandboxStatus(sandboxId));
    } catch (IOException e) {
      LOGGER.fine("Could not read status of sandbox " + sandboxId + ": " + e.getMessage());
      return false;
    }
  }

  /** Lists every sandbox visible to this API key. */
  public List<SandboxSummary> listSandboxes() throws IOException {
    JsonNode data = get("/v1/sandboxes");
    List<SandboxSummary> sandboxes = new ArrayList<>();
    for (JsonNode node : data.isArray() ? data : MAPPER.createArrayNode()) {
      sandboxes.add(
          new SandboxSummary(
              node.path("id").asText(),
              node.path("name").asText(""),
              node.path("status").asText("")));
    }
    return sandboxes;
  }

  /** Identity of one sandbox as the list endpoint reports it. */
  public record SandboxSummary(String id, String name, String status) {}

  /**
   * Run a bash script inside a sandbox and return once it exits.
   *
   * <p>POST /v1/sandboxes/{id}/exec Body: { "cmd": "bash", "args": ["-c", "..."] }
   */
  public JsonNode runBash(String sandboxId, String script) throws IOException {
    ObjectNode body = MAPPER.createObjectNode();
    body.put("cmd", "bash");
    body.putArray("args").add("-c").add(script);
    return post("/v1/sandboxes/" + sandboxId + "/exec", body);
  }

  /** Runs a shell script inside the sandbox and streams output into the Jenkins log. */
  public ExecResult runShellScript(
      String sandboxId, String script, PrintStream log, boolean collectStdout) throws IOException {
    ObjectNode body = MAPPER.createObjectNode();
    body.put("cmd", "bash");
    body.putArray("args").add("-lc").add(script);
    body.put("stream", true);

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/v1/sandboxes/" + sandboxId + "/exec?stream=true"))
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
            .header("x-api-key", apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "application/x-ndjson")
            .header("User-Agent", "createos-jenkins-plugin/0.1.0")
            .timeout(Duration.ofHours(1))
            .build();

    try {
      HttpResponse<InputStream> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() >= 400) {
        String responseBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
        throw new IOException(
            "CreateOS exec error: HTTP " + response.statusCode() + " — " + responseBody);
      }

      StringBuilder stdout = collectStdout ? new StringBuilder() : null;
      int exitCode = 0;
      try (var reader =
          new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.isBlank()) {
            continue;
          }
          JsonNode event = MAPPER.readTree(line);
          if (event.path("hb").asBoolean(false)) {
            continue;
          }
          if (event.hasNonNull("stdout")) {
            String value = event.get("stdout").asText();
            log.print(value);
            if (stdout != null) {
              stdout.append(value);
            }
          }
          if (event.hasNonNull("stderr")) {
            log.print(event.get("stderr").asText());
          }
          if (event.hasNonNull("error")) {
            log.println(event.get("error").asText());
          }
          if (event.has("exit_code")) {
            exitCode = event.get("exit_code").asInt();
          }
        }
      }
      return new ExecResult(exitCode, stdout == null ? null : stdout.toString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted during CreateOS exec", e);
    }
  }

  /** Uploads raw bytes to an absolute path inside the sandbox. */
  public void uploadFile(String sandboxId, String remotePath, InputStream input)
      throws IOException {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(fileUri(sandboxId, remotePath))
            .PUT(HttpRequest.BodyPublishers.ofInputStream(() -> input))
            .header("x-api-key", apiKey)
            .header("Content-Type", "application/octet-stream")
            .header("User-Agent", "createos-jenkins-plugin/0.1.0")
            .timeout(Duration.ofHours(1))
            .build();

    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        throw new IOException(
            "Failed to upload file to "
                + remotePath
                + ": HTTP "
                + response.statusCode()
                + " — "
                + response.body());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted during file transfer", e);
    }
  }

  /** Downloads raw bytes from an absolute path inside the sandbox. */
  public void downloadFile(String sandboxId, String remotePath, OutputStream output)
      throws IOException {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(fileUri(sandboxId, remotePath))
            .GET()
            .header("x-api-key", apiKey)
            .header("User-Agent", "createos-jenkins-plugin/0.1.0")
            .timeout(Duration.ofHours(1))
            .build();

    try {
      HttpResponse<InputStream> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() >= 400) {
        String responseBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
        throw new IOException(
            "Failed to download file "
                + remotePath
                + ": HTTP "
                + response.statusCode()
                + " — "
                + responseBody);
      }
      try (InputStream body = response.body()) {
        body.transferTo(output);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while downloading file", e);
    }
  }

  /**
   * Destroy a sandbox.
   *
   * <p>DELETE /v1/sandboxes/{id}
   */
  public void destroySandbox(String sandboxId) throws IOException {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/v1/sandboxes/" + sandboxId))
            .DELETE()
            .header("x-api-key", apiKey)
            .header("User-Agent", "createos-jenkins-plugin/0.1.0")
            .timeout(Duration.ofSeconds(30))
            .build();

    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400 && response.statusCode() != 404) {
        throw new IOException(
            "Failed to destroy sandbox " + sandboxId + ": HTTP " + response.statusCode());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while destroying sandbox", e);
    }
    LOGGER.fine("Destroyed sandbox: " + sandboxId);
  }

  /** Poll until sandbox reaches "running" state. */
  public void waitForRunning(String sandboxId, Duration timeout)
      throws IOException, InterruptedException {
    long deadline = System.currentTimeMillis() + timeout.toMillis();

    while (System.currentTimeMillis() < deadline) {
      String status = getSandboxStatus(sandboxId);

      if ("running".equals(status)) {
        LOGGER.fine("Sandbox " + sandboxId + " is running");
        return;
      }
      if ("error".equals(status)) {
        throw new IOException("Sandbox " + sandboxId + " entered error state");
      }

      Thread.sleep(2000);
    }

    throw new IOException("Timeout waiting for sandbox " + sandboxId + " to start");
  }

  // --- HTTP helpers ---

  private JsonNode get(String path) throws IOException {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .GET()
            .header("x-api-key", apiKey)
            .header("User-Agent", "createos-jenkins-plugin/0.1.0")
            .timeout(Duration.ofSeconds(120))
            .build();

    return executeAndUnwrap(request);
  }

  private JsonNode post(String path, ObjectNode body) throws IOException {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
            .header("x-api-key", apiKey)
            .header("Content-Type", "application/json")
            .header("User-Agent", "createos-jenkins-plugin/0.1.0")
            .timeout(Duration.ofSeconds(120))
            .build();

    return executeAndUnwrap(request);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private URI fileUri(String sandboxId, String remotePath) {
    return URI.create(baseUrl + "/v1/sandboxes/" + sandboxId + "/files?path=" + encode(remotePath));
  }

  /** Result from a CreateOS exec call. */
  public record ExecResult(int exitCode, String stdout) {}

  /**
   * Execute request and unwrap JSend response. Returns the "data" node from { "status": "success",
   * "data": {...} }
   */
  private JsonNode executeAndUnwrap(HttpRequest request) throws IOException {
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      String responseBody = response.body();

      if (response.statusCode() >= 400) {
        LOGGER.warning("API error: HTTP " + response.statusCode() + " " + responseBody);
        throw new IOException(
            "CreateOS API error: HTTP " + response.statusCode() + " — " + responseBody);
      }

      JsonNode root = MAPPER.readTree(responseBody);
      JsonNode data = root.get("data");
      if (data == null) {
        throw new IOException("Invalid API response: missing 'data' field");
      }
      return data;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted during API call", e);
    }
  }
}
