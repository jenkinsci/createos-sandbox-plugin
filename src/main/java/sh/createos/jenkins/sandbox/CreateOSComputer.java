package sh.createos.jenkins.sandbox;

import hudson.model.Executor;
import hudson.model.Label;
import hudson.model.Queue;
import hudson.slaves.AbstractCloudComputer;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/** Tracks task lifecycle events for a CreateOS agent. */
public class CreateOSComputer extends AbstractCloudComputer<CreateOSSlave> {

  private static final Logger LOGGER = Logger.getLogger(CreateOSComputer.class.getName());

  /** Creates the Jenkins computer for a registered CreateOS agent node. */
  public CreateOSComputer(CreateOSSlave slave) {
    super(slave);
    slave.getCreateOSCloud().markAgentRegistered(slave.getNodeName());
  }

  @Override
  public void taskAccepted(Executor executor, Queue.Task task) {
    super.taskAccepted(executor, task);
    LOGGER.fine("CreateOS agent " + getName() + " accepted task: " + task.getDisplayName());

    // This is a one-shot agent. Stop the queue from selecting it for another build
    // while its current build is still running, then provision for remaining work.
    setAcceptingTasks(false);

    CreateOSSlave node = getNode();
    if (node != null) {
      Label label = Jenkins.get().getLabel(node.getTemplate().getLabel());
      if (label != null) {
        label.nodeProvisioner.suggestReviewNow();
      }
    }
  }
}
