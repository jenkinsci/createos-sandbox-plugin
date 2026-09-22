package sh.createos.jenkins.sandbox;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import hudson.Extension;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.util.Collections;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/** Controller connects to sandbox port 22 through a CreateOS tunnel. */
public class SshLaunchMethod extends CreateOSLaunchMethod {

  private static final long serialVersionUID = 1L;

  private final String credentialsId;

  /** Creates the SSH transport backed by the given Jenkins SSH private key credential. */
  @DataBoundConstructor
  public SshLaunchMethod(String credentialsId) {
    this.credentialsId = credentialsId;
  }

  public String getCredentialsId() {
    return credentialsId;
  }

  SSHUserPrivateKey resolveCredential() {
    if (credentialsId == null || credentialsId.isBlank()) {
      throw new IllegalStateException("SSH launch requires SSH credentials");
    }
    SSHUserPrivateKey credential =
        CredentialsMatchers.firstOrNull(
            CredentialsProvider.lookupCredentialsInItemGroup(
                SSHUserPrivateKey.class, Jenkins.get(), ACL.SYSTEM2),
            CredentialsMatchers.withId(credentialsId));
    if (credential == null) {
      throw new IllegalStateException("SSH credential not found: " + credentialsId);
    }
    return credential;
  }

  /** Returns the authorized_keys line matching the configured credential. */
  String authorizedKey() throws IOException {
    return SshPublicKeys.authorizedKey(resolveCredential());
  }

  /** Exposes the SSH transport and its credential picker to the template form. */
  @Extension
  @Symbol("ssh")
  public static class DescriptorImpl extends CreateOSLaunchMethodDescriptor {

    @Override
    public String getDisplayName() {
      return "SSH over CreateOS tunnel";
    }

    /** Returns SSH credentials available to Jenkins administrators. */
    @POST
    public ListBoxModel doFillCredentialsIdItems(@QueryParameter String credentialsId) {
      if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
        return new StandardListBoxModel().includeCurrentValue(credentialsId);
      }
      return new StandardListBoxModel()
          .includeEmptyValue()
          .includeCurrentValue(credentialsId)
          .includeMatchingAs(
              ACL.SYSTEM2,
              Jenkins.get(),
              SSHUserPrivateKey.class,
              Collections.emptyList(),
              CredentialsMatchers.always());
    }

    /** Reports whether the public key can be derived from the selected credential. */
    @POST
    public FormValidation doCheckCredentialsId(@QueryParameter String value) {
      Jenkins.get().checkPermission(Jenkins.ADMINISTER);
      if (value == null || value.isBlank()) {
        return FormValidation.error("SSH credentials are required for SSH launch");
      }
      try {
        return FormValidation.ok(new SshLaunchMethod(value).authorizedKey());
      } catch (IOException | IllegalStateException e) {
        return FormValidation.error(e.getMessage());
      }
    }
  }
}
