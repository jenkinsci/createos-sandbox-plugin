package sh.createos.jenkins.sandbox;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.trilead.ssh2.crypto.PEMDecoder;
import hudson.util.Secret;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;
import java.util.List;
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil;
import org.bouncycastle.crypto.util.PublicKeyFactory;

/**
 * Derives the {@code authorized_keys} line for an SSH credential.
 *
 * <p>The public half of a key pair is computable from the private half, so the administrator
 * configures the credential and nothing else: there is no second field to paste, and no way to
 * paste a public key that does not match the private one the launcher authenticates with.
 *
 * <p>The private key is read with Trilead, the same library {@code SSHLauncher} authenticates with.
 * That is what makes the set of derivable keys exactly the set of usable keys: a key this class
 * rejects could not have logged in anyway, and — the case that matters — a passphrase-protected key
 * in OpenSSH's own container, which {@code ssh-keygen} produces by default, stays usable rather
 * than failing after the sandbox has been created.
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
  static String authorizedKey(String privateKeyPem, Secret passphrase) throws IOException {
    KeyPair keyPair = decode(privateKeyPem.trim(), passphrase);
    byte[] blob =
        OpenSSHPublicKeyUtil.encodePublicKey(
            PublicKeyFactory.createKey(keyPair.getPublic().getEncoded()));
    return keyType(blob) + " " + Base64.getEncoder().encodeToString(blob);
  }

  private static KeyPair decode(String privateKeyPem, Secret passphrase) throws IOException {
    try {
      return PEMDecoder.decodeKeyPair(privateKeyPem.toCharArray(), plainText(passphrase));
    } catch (IOException | RuntimeException e) {
      throw new IOException("Could not read the SSH private key: " + e.getMessage(), e);
    }
  }

  /** Reads the key type the blob names itself, so every algorithm spells its own prefix. */
  private static String keyType(byte[] blob) {
    int length = ByteBuffer.wrap(blob, 0, 4).getInt();
    return new String(blob, 4, length, StandardCharsets.US_ASCII);
  }

  private static String plainText(Secret passphrase) {
    if (passphrase == null || passphrase.getPlainText().isEmpty()) {
      return null;
    }
    return passphrase.getPlainText();
  }
}
