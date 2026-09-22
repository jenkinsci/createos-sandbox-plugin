package sh.createos.jenkins.sandbox;

import hudson.Extension;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

/** Agent connects back to the controller over the inbound WebSocket protocol. */
public class InboundLaunchMethod extends CreateOSLaunchMethod {

  private static final long serialVersionUID = 1L;

  /** Creates the inbound transport; it has nothing to configure. */
  @DataBoundConstructor
  public InboundLaunchMethod() {}

  /** Exposes the inbound transport to the template form, first so it stays the default. */
  @Extension(ordinal = 100)
  @Symbol("inbound")
  public static class DescriptorImpl extends CreateOSLaunchMethodDescriptor {

    @Override
    public String getDisplayName() {
      return "Inbound WebSocket";
    }
  }
}
