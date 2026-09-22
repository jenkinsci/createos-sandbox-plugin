package sh.createos.jenkins.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.util.Secret;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The fixtures are throwaway key pairs written by {@code ssh-keygen}, so the expected value is what
 * OpenSSH itself produced rather than a second copy of the code under test. Losing this property
 * would mean an agent whose {@code authorized_keys} line does not match the key the launcher
 * authenticates with — a failure that only appears at launch time, inside a sandbox.
 */
// Needs a real Jenkins: the BouncyCastle API plugin registers its provider at startup.
@WithJenkins
class SshPublicKeysTest {

  private static final String PASSPHRASE = "createos-test-passphrase";

  @ParameterizedTest
  @ValueSource(strings = {"ed25519", "rsa-pem", "ecdsa-pem"})
  void derivesThePublicKeySshKeygenWrote(String key, JenkinsRule r) throws Exception {
    assertEquals(sshKeygenPublicKey(key), SshPublicKeys.authorizedKey(read(key), null));
  }

  /**
   * Covers the key {@code ssh-keygen} writes by default when given a passphrase: its own container,
   * encrypted. The launcher can authenticate with one, so failing to derive its public half would
   * strand the agent after the sandbox had already been created and billed.
   */
  @ParameterizedTest
  @ValueSource(strings = {"ed25519-enc", "rsa-pem-enc"})
  void derivesThePublicKeyOfAnEncryptedKey(String key, JenkinsRule r) throws Exception {
    assertEquals(
        sshKeygenPublicKey(key),
        SshPublicKeys.authorizedKey(read(key), Secret.fromString(PASSPHRASE)));
  }

  @Test
  void rejectsGarbageInsteadOfPrivateKey(JenkinsRule r) {
    IOException thrown =
        assertThrows(IOException.class, () -> SshPublicKeys.authorizedKey("not a key", null));

    assertTrue(thrown.getMessage() != null, "failure must explain itself to the administrator");
  }

  /** The two leading fields of an OpenSSH public key: its type and the key itself, no comment. */
  private static String sshKeygenPublicKey(String key) throws IOException {
    String[] fields = read(key + ".pub").trim().split(" ");
    return fields[0] + " " + fields[1];
  }

  private static String read(String name) throws IOException {
    try (InputStream in = SshPublicKeysTest.class.getResourceAsStream("ssh/" + name)) {
      if (in == null) {
        throw new IOException("missing test fixture: " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
