package sh.createos.jenkins.sandbox;

import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import java.io.Serializable;

/**
 * How a CreateOS agent connects back to the controller.
 *
 * <p>Describable rather than a string choice so each transport carries only its own configuration:
 * the SSH credential lives on {@link SshLaunchMethod} and is therefore absent from the form — and
 * from the persisted template — whenever the inbound transport is selected.
 */
public abstract class CreateOSLaunchMethod extends AbstractDescribableImpl<CreateOSLaunchMethod>
    implements Serializable {

  private static final long serialVersionUID = 1L;

  /** Base descriptor so the template form lists only CreateOS transports. */
  public abstract static class CreateOSLaunchMethodDescriptor
      extends Descriptor<CreateOSLaunchMethod> {}
}
