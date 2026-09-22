package sh.createos.jenkins.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers what the end-to-end run cannot: that a value reaching {@code bash -c} stays one argument,
 * and that the baked-jar branch is chosen on its own merits.
 *
 * <p>The e2e suite is blind to both. It deploys Jenkins 2.568.2, whose embedded remoting is the
 * very version the template bakes, so the baked branch and the controller-download branch produce
 * an identical jar and every smoke test passes either way.
 *
 * <p>Every test here runs the string {@link CreateOSLauncher#prepareCommand} actually returns,
 * through a real bash. Nothing restates the script, so the test cannot drift away from the launcher
 * — editing the launcher changes what these tests execute.
 */
class CreateOSLauncherTest {

  // --- shell quoting -------------------------------------------------------

  @Test
  void quotingWrapsAnOrdinaryPathWithoutChangingIt() {
    assertEquals("'/home/jenkins'", CreateOSLauncher.shellQuote("/home/jenkins"));
  }

  @Test
  void quotingClosesAndReopensAroundAnApostrophe() {
    // rootfs sits inside a quoted message; a bare apostrophe would end that string early.
    assertEquals("'it'\\''s'", CreateOSLauncher.shellQuote("it's"));
  }

  @Test
  void quotingHandlesNullRatherThanEmittingTheWordNull() {
    assertEquals("''", CreateOSLauncher.shellQuote(null));
  }

  /**
   * The property that matters, checked by running a shell rather than by comparing strings: a
   * hostile value must arrive as one argument, byte for byte, and must not execute.
   */
  @Test
  void aQuotedValueReachesBashAsExactlyOneUnexecutedArgument(@TempDir Path tmp) throws Exception {
    Path canary = tmp.resolve("pwned");
    String hostile = "/home/jenkins; touch " + canary + "; #";

    String stdout =
        runBash("printf %s " + CreateOSLauncher.shellQuote(hostile) + " > " + tmp.resolve("out"));

    assertEquals("", stdout);
    assertEquals(hostile, Files.readString(tmp.resolve("out")));
    assertFalse(Files.exists(canary), "injected command ran — quoting failed");
  }

  @Test
  void anUnquotedHostileValueWouldHaveExecuted(@TempDir Path tmp) throws Exception {
    // Guards the test itself: proves the payload is genuinely dangerous unquoted, so the
    // assertion above is evidence of quoting rather than of a harmless string.
    Path canary = tmp.resolve("pwned");

    runBash("echo /home/jenkins; touch " + canary + "; #");

    assertTrue(Files.exists(canary), "payload is inert; the quoting test proves nothing");
  }

  /** A workspace path containing a space is ordinary, not hostile, and must still work. */
  @Test
  void aRemoteFsContainingASpaceStillResolvesToOnePath(@TempDir Path tmp) throws Exception {
    Path workspace = tmp.resolve("my agent workspace");
    Path baked = writeJar(tmp.resolve("agent.jar"));

    runPrepare(baked, workspace, tmp);

    assertTrue(Files.exists(workspace.resolve("agent.jar")), "space in remoteFs split the path");
  }

  @Test
  void sshPreparationRunsAndUsesTheCredentialUsernameHomeDirectory(@TempDir Path tmp)
      throws Exception {
    Path home = tmp.resolve("builder-home");
    Path bin = tmp.resolve("bin");
    Files.createDirectories(home);
    Files.createDirectories(bin);
    writeExecutable(
        bin.resolve("getent"),
        "#!/usr/bin/env bash\n"
            + "if [ \"$1\" = passwd ] && [ \"$2\" = builder ]; then\n"
            + "  echo \"builder:x:1000:1000::"
            + home
            + ":/bin/bash\"\n"
            + "fi\n");
    writeExecutable(bin.resolve("java"), "#!/usr/bin/env bash\nexit 0\n");
    writeExecutable(bin.resolve("ssh-keygen"), "#!/usr/bin/env bash\nexit 0\n");
    writeExecutable(bin.resolve("pgrep"), "#!/usr/bin/env bash\nexit 1\n");
    writeExecutable(bin.resolve("chown"), "#!/usr/bin/env bash\nexit 0\n");
    writeExecutable(
        bin.resolve("sshd"),
        "#!/usr/bin/env bash\n" + "printf 'sshd-started' > " + tmp.resolve("sshd.log") + "\n");
    SandboxTemplate template = new SandboxTemplate("createos", "s-1vcpu-1gb", "devbox:1");
    template.setRemoteFs(tmp.resolve("workspace").toString());
    template.setSshPublicKey("ssh-ed25519 AAAATEST createos");

    String command =
        CreateOSLauncher.sshPrepareCommand(template, "builder", tmp.resolve("run-sshd").toString());
    String out =
        runBash(
            command, Map.of("PATH", bin + System.getProperty("path.separator") + "/usr/bin:/bin"));

    assertEquals("", out);
    assertEquals(
        "ssh-ed25519 AAAATEST createos\n", Files.readString(home.resolve(".ssh/authorized_keys")));
    assertEquals("sshd-started", Files.readString(tmp.resolve("sshd.log")));
  }

  // --- baked jar selection, running the launcher's own script --------------

  @Test
  void aRealJarTakesTheBakedBranch(@TempDir Path tmp) throws Exception {
    Path baked = writeJar(tmp.resolve("agent.jar"));
    Path workspace = tmp.resolve("ws");

    String out = runPrepare(baked, workspace, tmp);

    assertTrue(out.contains("baked into rootfs"), out);
    assertFalse(out.contains("downloading from controller"), out);
    assertSameBytes(baked, workspace.resolve("agent.jar"));
  }

  @Test
  void aMissingJarFallsBackToTheController(@TempDir Path tmp) throws Exception {
    Path fallbackSource = writeJar(tmp.resolve("from-controller.jar"));
    Path workspace = tmp.resolve("ws");

    String out = runPrepare(tmp.resolve("absent.jar"), workspace, fallbackSource);

    assertTrue(out.contains("downloading from controller"), out);
    assertSameBytes(fallbackSource, workspace.resolve("agent.jar"));
  }

  @Test
  void anEmptyJarFallsBackInsteadOfBeingCopied(@TempDir Path tmp) throws Exception {
    Path baked = tmp.resolve("agent.jar");
    Files.createFile(baked);
    Path fallbackSource = writeJar(tmp.resolve("from-controller.jar"));
    Path workspace = tmp.resolve("ws");

    String out = runPrepare(baked, workspace, fallbackSource);

    assertTrue(out.contains("unusable"), out);
    assertSameBytes(fallbackSource, workspace.resolve("agent.jar"));
  }

  @Test
  void aTruncatedOrWrongFileFallsBackInsteadOfBeingCopied(@TempDir Path tmp) throws Exception {
    // The case a readability check misses: present and readable, but not a jar. Copying it
    // would defer the failure to the JNLP handshake, where the cause is no longer visible.
    Path baked = tmp.resolve("agent.jar");
    Files.writeString(baked, "<html>404 Not Found</html>");
    Path fallbackSource = writeJar(tmp.resolve("from-controller.jar"));
    Path workspace = tmp.resolve("ws");

    String out = runPrepare(baked, workspace, fallbackSource);

    assertTrue(out.contains("unusable"), out);
    assertSameBytes(fallbackSource, workspace.resolve("agent.jar"));
  }

  // --- helpers -------------------------------------------------------------

  /**
   * Runs the launcher's real command. The controller download is a {@code file://} URL so the
   * fallback branch is exercised without a network or a Jenkins.
   */
  private static String runPrepare(Path bakedJar, Path workspace, Path fallbackSource)
      throws IOException, InterruptedException {
    return runBash(
        CreateOSLauncher.prepareCommand(
            bakedJar.toString(), workspace.toString(), "devbox:1", "file://" + fallbackSource));
  }

  private static Path writeJar(Path path) throws IOException {
    Files.write(path, new byte[] {'P', 'K', 3, 4, 0, 0, 0, 0});
    return path;
  }

  private static void assertSameBytes(Path expected, Path actual) throws IOException {
    assertTrue(Files.exists(actual), actual + " was never created");
    assertTrue(
        java.util.Arrays.equals(Files.readAllBytes(expected), Files.readAllBytes(actual)),
        "wrong jar landed in the workspace");
  }

  private static String runBash(String script) throws IOException, InterruptedException {
    return runBash(script, Map.of());
  }

  private static String runBash(String script, Map<String, String> environment)
      throws IOException, InterruptedException {
    ProcessBuilder builder =
        new ProcessBuilder(List.of("bash", "-c", script)).redirectErrorStream(true);
    builder.environment().putAll(environment);
    Process p = builder.start();
    String out = new String(p.getInputStream().readAllBytes());
    int exitCode = p.waitFor();
    if (exitCode != 0) {
      throw new IOException("bash exited " + exitCode + ": " + out);
    }
    return out;
  }

  private static void writeExecutable(Path path, String content) throws IOException {
    Files.writeString(path, content);
    path.toFile().setExecutable(true);
  }
}
