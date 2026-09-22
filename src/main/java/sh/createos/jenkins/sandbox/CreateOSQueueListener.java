package sh.createos.jenkins.sandbox;

import hudson.Extension;
import hudson.model.Label;
import hudson.model.Queue;
import hudson.model.queue.QueueListener;
import hudson.slaves.Cloud;
import hudson.slaves.Cloud.CloudState;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.util.Timer;

/** Requests an immediate provisioning review when matching work becomes buildable. */
@Extension
public class CreateOSQueueListener extends QueueListener {

  private static final Logger LOGGER = Logger.getLogger(CreateOSQueueListener.class.getName());

  @Override
  public void onEnterBuildable(Queue.BuildableItem item) {
    Label label = item.getAssignedLabel();
    if (label == null) {
      return;
    }

    for (Cloud cloud : Jenkins.get().clouds) {
      if (cloud instanceof CreateOSCloud && cloud.canProvision(new CloudState(label, 0))) {
        LOGGER.fine("Requesting immediate provisioning review for label: " + label.getName());
        label.nodeProvisioner.suggestReviewNow();

        // Jenkins coalesces suggestions while a review is already queued. A burst
        // can therefore arrive after that review measured the queue and be missed
        // until the first agent accepts work. Recheck once after the debounce window.
        Timer.get().schedule(label.nodeProvisioner::suggestReviewNow, 1200, TimeUnit.MILLISECONDS);
        return;
      }
    }
  }
}
