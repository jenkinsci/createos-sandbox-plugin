package sh.createos.jenkins.sandbox;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Local loopback TCP proxy that forwards each connection into a CreateOS tunnel. */
final class CreateOSTunnelProxy implements AutoCloseable {

  private static final Logger LOGGER = Logger.getLogger(CreateOSTunnelProxy.class.getName());
  private static final int MAX_HTTP_LINE_BYTES = 8192;

  private final CreateOSApiClient client;
  private final String sandboxId;
  private final int sandboxPort;
  private final ServerSocket server;
  private final ExecutorService executor;
  private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();

  CreateOSTunnelProxy(CreateOSApiClient client, String sandboxId, int sandboxPort)
      throws IOException {
    this.client = client;
    this.sandboxId = sandboxId;
    this.sandboxPort = sandboxPort;
    this.server = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
    this.executor =
        Executors.newCachedThreadPool(
            runnable -> {
              Thread thread = new Thread(runnable, "CreateOS tunnel proxy " + sandboxId);
              thread.setDaemon(true);
              return thread;
            });
  }

  int getLocalPort() {
    return server.getLocalPort();
  }

  void start() {
    executor.submit(this::acceptLoop);
  }

  private void acceptLoop() {
    while (!server.isClosed()) {
      try {
        Socket local = server.accept();
        executor.submit(() -> handle(local));
      } catch (IOException e) {
        if (!server.isClosed()) {
          LOGGER.log(Level.WARNING, "CreateOS tunnel proxy accept failed", e);
          try {
            TimeUnit.MILLISECONDS.sleep(250);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
          }
        }
      }
    }
  }

  private void handle(Socket local) {
    Socket remote = null;
    try {
      sockets.add(local);
      remote = openTunnel();
      sockets.add(remote);
      Socket remoteSocket = remote;
      executor.submit(
          () -> {
            copy(local, remoteSocket);
          });
      copy(remoteSocket, local);
    } catch (IOException e) {
      LOGGER.log(Level.FINE, "CreateOS tunnel proxy connection closed", e);
    } finally {
      closeSocket(local);
      closeSocket(remote);
      sockets.remove(local);
      if (remote != null) {
        sockets.remove(remote);
      }
    }
  }

  private static void copy(Socket from, Socket to) {
    try {
      from.getInputStream().transferTo(to.getOutputStream());
    } catch (IOException ignored) {
      // The peer closed one side of the tunnel.
    } finally {
      try {
        to.shutdownOutput();
      } catch (IOException ignored) {
        // Already closed.
      }
    }
  }

  /** Opens a raw TCP tunnel through the CreateOS API to the configured sandbox port. */
  private Socket openTunnel() throws IOException {
    URI uri =
        URI.create(client.baseUrl() + "/v1/sandboxes/" + sandboxId + "/tunnel/" + sandboxPort);
    String scheme = uri.getScheme();
    int remotePort = uri.getPort();
    if (remotePort < 0) {
      remotePort = "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    Socket socket;
    if ("https".equalsIgnoreCase(scheme)) {
      socket = SSLSocketFactory.getDefault().createSocket(uri.getHost(), remotePort);
      SSLParameters parameters = ((SSLSocket) socket).getSSLParameters();
      parameters.setEndpointIdentificationAlgorithm("HTTPS");
      ((SSLSocket) socket).setSSLParameters(parameters);
      ((SSLSocket) socket).startHandshake();
    } else if ("http".equalsIgnoreCase(scheme)) {
      socket = new Socket(uri.getHost(), remotePort);
    } else {
      throw new IOException("Unsupported CreateOS API scheme for tunnel: " + scheme);
    }
    socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(30));

    String path = uri.getRawPath();
    String query = uri.getRawQuery();
    if (query != null && !query.isBlank()) {
      path += "?" + query;
    }
    String request =
        "POST "
            + path
            + " HTTP/1.1\r\n"
            + "Host: "
            + hostHeader(uri, remotePort)
            + "\r\n"
            + "x-api-key: "
            + client.apiKey()
            + "\r\n"
            + "User-Agent: "
            + CreateOSApiClient.USER_AGENT
            + "\r\n"
            + "Connection: Upgrade\r\n"
            + "Upgrade: tcp-tunnel\r\n"
            + "Content-Length: 0\r\n"
            + "\r\n";
    socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
    socket.getOutputStream().flush();

    String status = readHttpLine(socket.getInputStream());
    if (status == null || parseStatusCode(status) != 101) {
      StringBuilder response = new StringBuilder(status == null ? "" : status);
      String line;
      while ((line = readHttpLine(socket.getInputStream())) != null && !line.isEmpty()) {
        response.append('\n').append(line);
      }
      socket.close();
      throw new IOException("CreateOS tunnel failed: " + response);
    }
    String line;
    while ((line = readHttpLine(socket.getInputStream())) != null && !line.isEmpty()) {
      // Discard upgrade headers.
    }
    socket.setSoTimeout((int) TimeUnit.MINUTES.toMillis(10));
    return socket;
  }

  private static int parseStatusCode(String status) throws IOException {
    String[] parts = status.split(" ", 3);
    if (parts.length < 2) {
      throw new IOException("Invalid CreateOS tunnel HTTP status line: " + status);
    }
    try {
      return Integer.parseInt(parts[1]);
    } catch (NumberFormatException e) {
      throw new IOException("Invalid CreateOS tunnel HTTP status code: " + status, e);
    }
  }

  private static String hostHeader(URI uri, int port) {
    boolean defaultPort =
        ("https".equalsIgnoreCase(uri.getScheme()) && port == 443)
            || ("http".equalsIgnoreCase(uri.getScheme()) && port == 80);
    return defaultPort ? uri.getHost() : uri.getHost() + ":" + port;
  }

  private static String readHttpLine(InputStream input) throws IOException {
    StringBuilder line = new StringBuilder();
    boolean seenCR = false;
    while (true) {
      int b;
      try {
        b = input.read();
      } catch (SocketTimeoutException e) {
        throw new IOException("Timed out waiting for CreateOS tunnel response", e);
      }
      if (b < 0) {
        return line.isEmpty() ? null : line.toString();
      }
      if (line.length() >= MAX_HTTP_LINE_BYTES) {
        throw new IOException("CreateOS tunnel response line exceeded " + MAX_HTTP_LINE_BYTES);
      }
      if (seenCR) {
        if (b == '\n') {
          return line.toString();
        }
        line.append('\r');
        seenCR = false;
      }
      if (b == '\r') {
        seenCR = true;
      } else {
        line.append((char) b);
      }
    }
  }

  @Override
  public void close() {
    try {
      server.close();
    } catch (IOException e) {
      LOGGER.log(Level.FINE, "CreateOS tunnel proxy close failed", e);
    }
    for (Socket socket : sockets) {
      closeSocket(socket);
    }
    executor.shutdownNow();
  }

  private static void closeSocket(Socket socket) {
    if (socket == null) {
      return;
    }
    try {
      socket.close();
    } catch (IOException ignored) {
      // Already closed.
    }
  }
}
