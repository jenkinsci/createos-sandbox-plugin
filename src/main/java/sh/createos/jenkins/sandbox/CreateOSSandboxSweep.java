package sh.createos.jenkins.sandbox;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.Cloud;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import sh.createos.jenkins.sandbox.CreateOSApiClient.SandboxSummary;

/**
 * Destroys sandboxes this controller created but no longer has an agent for.
 *
 * <p>This is what makes it safe for {@link CreateOSCloud#recoverStaleAgents()} to reconnect an
 * agent instead of terminating it. Reconnecting accepts that a sandbox can outlive the controller
 * process; without a sweep, a node that disappears while the controller is down — deleted by an
 * administrator, lost with a rolled-back {@code JENKINS_HOME}, or abandoned when a reconnect
 * deadline passed unobserved — would leave a microVM billing with nothing left to attribute it to.
 * Terminating on startup used to rule that out by construction.
 *
 * <p>Only sandboxes named by this controller are considered, so a sandbox belonging to another
 * Jenkins instance on the same CreateOS account, or to a human, is never touched.
 */
@Extension
public class CreateOSSandboxSweep extends AsyncPeriodicWork {

  private static final Logger LOGGER = Logger.getLogger(CreateOSSandboxSweep.class.getName());
  private static final long INTERVAL_MINUTES = 10;

  /** Registers the sweep with Jenkins' periodic work scheduler. */
  public CreateOSSandboxSweep() {
    super("CreateOS orphaned sandbox sweep");
  }

  @Override
  public long getRecurrencePeriod() {
    return TimeUnit.MINUTES.toMillis(INTERVAL_MINUTES);
  }

  @Override
  protected void execute(TaskListener listener) {
    for (Cloud configured : Jenkins.get().clouds) {
      if (configured instanceof CreateOSCloud cloud) {
        try {
          sweep(cloud);
        } catch (Exception e) {
          // A cloud that cannot be reached keeps its sandboxes; the next pass tries again.
          LOGGER.log(Level.FINE, "CreateOS sweep skipped cloud " + cloud.name, e);
        }
      }
    }
  }

  private static void sweep(CreateOSCloud cloud) throws Exception {
    CreateOSApiClient client = cloud.buildApiClient();
    List<SandboxSummary> orphans =
        reclaimable(client.listSandboxes(), claimedSandboxIds(), pendingSandboxNames(cloud));

    for (SandboxSummary orphan : orphans) {
      try {
        LOGGER.info("Reclaiming orphaned CreateOS sandbox: " + orphan.name());
        client.destroySandbox(orphan.id());
      } catch (Exception e) {
        LOGGER.log(Level.WARNING, "Could not reclaim sandbox " + orphan.id(), e);
      }
    }
  }

  /** Every sandbox id some node on this controller still answers for. */
  private static Set<String> claimedSandboxIds() {
    Set<String> claimed = new HashSet<>();
    for (Node node : Jenkins.get().getNodes()) {
      if (node instanceof CreateOSSlave slave && slave.getSandboxId() != null) {
        claimed.add(slave.getSandboxId());
      }
    }
    return claimed;
  }

  /**
   * Names of sandboxes being provisioned right now. A launch in flight has already created its
   * sandbox but has not yet recorded the id on a node, so its id cannot be matched — the name can,
   * because it is derived from the agent name the cloud is already tracking.
   */
  private static Set<String> pendingSandboxNames(CreateOSCloud cloud) {
    Set<String> pending = new HashSet<>();
    for (String agentName : cloud.pendingAgentNames()) {
      pending.add(CreateOSSlave.sandboxName(agentName));
    }
    return pending;
  }

  /**
   * Selects the sandboxes that belong to this controller but to no live or planned agent.
   *
   * <p>Matched by sandbox id rather than by name, because the API's 22-character name cap leaves no
   * room to encode which agent a sandbox belongs to. The node persists the id, so that is the link;
   * the name only says which controller created it.
   *
   * <p>Separated from the API calls so the decision can be tested directly: the cost of getting it
   * wrong is either a leaked microVM or, worse, destroying a sandbox out from under a running
   * build.
   */
  static List<SandboxSummary> reclaimable(
      List<SandboxSummary> sandboxes, Set<String> claimedSandboxIds, Set<String> pendingNames) {
    List<SandboxSummary> orphans = new ArrayList<>();
    for (SandboxSummary sandbox : sandboxes) {
      if (!CreateOSSlave.namedByThisController(sandbox.name())) {
        continue; // Another controller's sandbox, or a human's.
      }
      if (claimedSandboxIds.contains(sandbox.id())) {
        continue; // Claimed by a live agent.
      }
      if (pendingNames.contains(sandbox.name())) {
        continue; // Provisioning is still in flight; the node does not exist yet.
      }
      orphans.add(sandbox);
    }
    return orphans;
  }
}
