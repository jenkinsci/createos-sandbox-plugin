package sh.createos.jenkins.sandbox;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import hudson.util.Secret;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Iterator;
import java.util.List;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.util.security.SecurityUtils;

/**
 * Derives the {@code authorized_keys} line for an SSH credential.
 *
 * <p>The public half of a key pair is computable from the private half, so the administrator
 * configures the credential and nothing else: there is no second field to paste, and no way to
 * paste a public key that does not match the private one the launcher authenticates with.
 *
 * <p>The private key is read with Apache MINA SSHD rather than Trilead. Trilead is an abandoned
 * library Jenkins keeps alive in a fork, reachable here only transitively through ssh-slaves, which
 * is itself moving to MINA. MINA also reads a passphrase-protected key in OpenSSH's own container —
 * what {@code ssh-keygen} writes by default — which BouncyCastle alone cannot, since that format is
 * encrypted with bcrypt-pbkdf. Rejecting such a key would strand the agent after its sandbox had
 * already been created.
 */
final class SshPublicKeys {

  private SshPublicKeys() {}

  /** Returns the OpenSSH public key matching the credential's private key. */
  static String authorizedKey(SSHUserPrivateKey credential) throws IOException {
    List<String> privateKeys = credential.getPrivateKeys();
    if (privateKeys.isEmpty()) {
      throw new IOException("SSH credential has no private key: " + credential.getId());
    }
    return authorizedKey(privateKeys.get(0), credential.getPassphrase());
  }

  /** Returns the OpenSSH public key matching a private key in PEM or OpenSSH format. */
  static String authorizedKey(String privateKey, Secret passphrase) throws IOException {
    return PublicKeyEntry.toString(decode(privateKey.trim(), passphrase).getPublic());
  }

  private static KeyPair decode(String privateKey, Secret passphrase) throws IOException {
    try {
      Iterable<KeyPair> keys =
          SecurityUtils.loadKeyPairIdentities(
              null,
              NamedResource.ofName("SSH credential"),
              new ByteArrayInputStream(privateKey.getBytes(StandardCharsets.UTF_8)),
              passwordProvider(passphrase));
      Iterator<KeyPair> first = keys == null ? null : keys.iterator();
      if (first == null || !first.hasNext()) {
        throw new IOException("no private key found");
      }
      return first.next();
    } catch (IOException | GeneralSecurityException | RuntimeException e) {
      throw new IOException("Could not read the SSH private key: " + e.getMessage(), e);
    }
  }

  private static FilePasswordProvider passwordProvider(Secret passphrase) {
    if (passphrase == null || passphrase.getPlainText().isEmpty()) {
      return FilePasswordProvider.EMPTY;
    }
    return FilePasswordProvider.of(passphrase.getPlainText());
  }
}
