package sh.createos.jenkins.sandbox;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.listeners.ItemListener;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.slaves.SlaveComputer;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/** Jenkins cloud implementation that provisions ephemeral CreateOS sandbox agents. */
public class CreateOSCloud extends Cloud {

  private static final int DEFAULT_SANDBOX_CAP = 100;

  private static final Logger LOGGER = Logger.getLogger(CreateOSCloud.class.getName());

  /** How long a recovered agent has to come back online before its sandbox is reclaimed. */
  private static final long RECONNECT_DEADLINE_MINUTES = 10;

  private String apiUrl;
  private String displayName;
  private String credentialsId;
  private int containerCap;
  private int execSandboxCap;
  private List<SandboxTemplate> templates;
  private transient Set<String> pendingAgentNames = ConcurrentHashMap.newKeySet();
  private transient Set<String> activeExecSandboxNames = ConcurrentHashMap.newKeySet();
  private transient ConcurrentHashMap<String, SandboxTemplate> pipelineTemplates =
      new ConcurrentHashMap<>();

  /** Creates a cloud with the default CreateOS API endpoint and capacity. */
  @DataBoundConstructor
  public CreateOSCloud(String name) {
    super(name);
    this.apiUrl = "https://api.sb.createos.sh";
    this.containerCap = DEFAULT_SANDBOX_CAP;
    this.execSandboxCap = DEFAULT_SANDBOX_CAP;
    this.templates = new ArrayList<>();
  }

  /**
   * Decides what to do with each CreateOS agent restored from an earlier controller process.
   *
   * <p>An agent whose sandbox is still running is kept rather than destroyed: the sandbox kept its
   * workspace, so a Pipeline that was mid-build can resume through Durable Task. An inbound agent's
   * process is still running in that sandbox and reconnects its WebSocket by itself; an SSH agent
   * only lost its tunnel with the previous JVM, and Jenkins relaunches it through a new one.
   *
   * <p>A template can opt out with "Delete agents on controller restart", for agents meant to be
   * strictly one-process-lifetime; those are terminated, as is a node whose sandbox has gone and so
   * has nothing left to reconnect to.
   *
   * <p>Driven from {@link ItemListener#onLoaded()} rather than an {@code @Initializer}: the work
   * needs {@link Computer} objects, which exist only once startup is finished, and
   * {@code @Initializer(after = COMPLETED)} can never run because that milestone is terminal —
   * scheduling against it wedges the initialization graph instead (JENKINS-37759).
   */
  public static void recoverStaleAgents() {
    for (CreateOSSlave node : agents().map(CreateOSSlave.class::cast).toList()) {
      if (isRecoverable(node)) {
        reconnect(node);
      } else {
        terminateQuietly(node);
      }
    }
  }

  /** Runs {@link #recoverStaleAgents()} once Jenkins has finished starting. */
  @Extension
  public static class AgentRecovery extends ItemListener {

    @Override
    public void onLoaded() {
      recoverStaleAgents();
    }
  }

  /**
   * Whether an agent from a previous controller process should be reconnected: a sandbox that is
   * still running, and a template that has not opted into deletion on restart.
   */
  static boolean isRecoverable(CreateOSSlave node) {
    if (node.getSandboxId() == null || node.getTemplate().isDeleteOnRestart()) {
      return false;
    }
    try {
      CreateOSCloud cloud = node.getCreateOSCloud();
      return cloud != null && cloud.buildApiClient().isRunning(node.getSandboxId());
    } catch (Exception e) {
      LOGGER.log(Level.FINE, "Could not check sandbox for " + node.getNodeName(), e);
      return false;
    }
  }

  /**
   * Keeps a recoverable agent alive and lets Jenkins connect it.
   *
   * <p>Deliberately does not launch the agent itself. Jenkins connects a restored node through its
   * retention strategy once the computer list is built, and this runs earlier — from {@code
   * onLoaded()}, before that happens. Launching here as well produced two connections to the same
   * computer: the manual one completed a full SSH handshake and remoting handshake, then died on
   * {@code IllegalStateException: Already connected} in {@link SlaveComputer#setChannel}, taking
   * the working channel down with it and leaving the build waiting for an agent that never
   * returned. Not terminating the node is the whole of what recovery has to do.
   */
  private static void reconnect(CreateOSSlave node) {
    Computer computer = node.toComputer();
    if (!(computer instanceof SlaveComputer slaveComputer)) {
      terminateQuietly(node);
      return;
    }
    LOGGER.fine("Keeping CreateOS agent with a surviving sandbox: " + node.getNodeName());

    // A reconnection that never completes would hold a sandbox open indefinitely, so the
    // agent gets a deadline rather than the benefit of the doubt.
    Timer.get()
        .schedule(
            () -> {
              if (slaveComputer.isOffline()) {
                LOGGER.warning(
                    "CreateOS agent did not come back online, terminating: " + node.getNodeName());
                terminateQuietly(node);
              }
            },
            RECONNECT_DEADLINE_MINUTES,
            TimeUnit.MINUTES);
  }

  private static void terminateQuietly(CreateOSSlave node) {
    try {
      LOGGER.info("Terminating stale agent, nothing to reconnect to: " + node.getNodeName());
      node.terminate();
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Failed to terminate stale agent", e);
    }
  }

  // --- Core Cloud methods ---

  @Override
  public boolean canProvision(CloudState state) {
    return getTemplateFor(state.getLabel()) != null;
  }

  @Override
  public Collection<PlannedNode> provision(CloudState state, int excessWorkload) {
    Label label = state.getLabel();
    SandboxTemplate template = getTemplateFor(label);
    if (template == null) {
      return Collections.emptyList();
    }

    int currentCount = countCurrentAgents();
    int pendingCount = countPendingAgents();
    if (currentCount + pendingCount >= containerCap) {
      LOGGER.fine("Container cap reached (" + containerCap + "), cannot provision more");
      return Collections.emptyList();
    }

    int toProvision = Math.min(excessWorkload, containerCap - currentCount - pendingCount);
    if (toProvision == 0) {
      return Collections.emptyList();
    }

    LOGGER.fine("CreateOS provisioning " + toProvision + " agent(s)");
    List<PlannedNode> planned = new ArrayList<>();

    for (int i = 0; i < toProvision; i++) {
      String agentName =
          "createos-" + template.getLabel() + "-" + System.currentTimeMillis() + "-" + i;
      getPendingAgentNames().add(agentName);

      Future<Node> future =
          Computer.threadPoolForRemoting.submit(
              () -> {
                try {
                  LOGGER.fine("Provisioning CreateOS sandbox agent: " + agentName);
                  Node node = new CreateOSSlave(agentName, template, this);

                  /*
                   * NodeProvisioner does not wake when a PlannedNode future completes.
                   * Defer the review so its current update can first record this future.
                   *
                   * Skipped entirely for a null label, which is what `agent any` produces:
                   * there is no per-label provisioner to nudge, and dereferencing it here
                   * threw an NPE inside this future. Losing the hint only means unlabelled
                   * work waits for the provisioner's next ordinary cycle instead of being
                   * woken early — slower, never stuck.
                   */
                  if (label != null) {
                    Timer.get()
                        .schedule(
                            label.nodeProvisioner::suggestReviewNow, 100, TimeUnit.MILLISECONDS);
                  }
                  return node;
                } finally {
                  getPendingAgentNames().remove(agentName);
                }
              });

      planned.add(new PlannedNode(agentName, future, 1));
    }

    return planned;
  }

  // --- Helper methods ---

  private SandboxTemplate getTemplateFor(Label label) {
    SandboxTemplate pipelineTemplate = getPipelineTemplate(label);
    if (pipelineTemplate != null) {
      return pipelineTemplate;
    }
    // A null label is what `agent any` produces. No template serves it: CreateOSSlave is
    // EXCLUSIVE, so a sandbox provisioned for an unlabelled job can never accept it. Matching
    // the first template here made canProvision(null) true, and a controller with zero
    // executors then created a sandbox on every provisioner cycle that nothing could use.
    if (label == null) {
      return null;
    }
    for (SandboxTemplate template : templates) {
      if (label.matches(Label.parse(template.getLabel()))) {
        return template;
      }
    }
    return null;
  }

  private SandboxTemplate getPipelineTemplate(Label label) {
    if (label == null) {
      return null;
    }
    return getPipelineTemplates().get(label.getName());
  }

  SandboxTemplate getTemplateByLabel(String label) {
    if (label == null || label.isBlank()) {
      return null;
    }
    for (SandboxTemplate template : templates) {
      if (label.equals(template.getLabel())) {
        return template;
      }
    }
    return null;
  }

  void registerPipelineTemplate(String label, SandboxTemplate template) {
    getPipelineTemplates().put(label, template);
  }

  void unregisterPipelineTemplate(String label) {
    if (label != null) {
      getPipelineTemplates().remove(label);
    }
  }

  private ConcurrentHashMap<String, SandboxTemplate> getPipelineTemplates() {
    if (pipelineTemplates == null) {
      pipelineTemplates = new ConcurrentHashMap<>();
    }
    return pipelineTemplates;
  }

  // A newly registered node may not be online, connecting, or have a sandbox ID yet. It still
  // represents capacity Jenkins already requested and must count toward the cap.
  private int countCurrentAgents() {
    return (int) ownedAgents().count();
  }

  private static Stream<Node> agents() {
    return Jenkins.get().getNodes().stream().filter(CreateOSSlave.class::isInstance);
  }

  private Stream<CreateOSSlave> ownedAgents() {
    return agents()
        .map(CreateOSSlave.class::cast)
        .filter(agent -> name.equals(agent.getCloudName()));
  }

  private int countPendingAgents() {
    getPendingAgentNames().removeAll(ownedAgents().map(Node::getNodeName).toList());
    return getPendingAgentNames().size();
  }

  /** Agents planned but not yet added as nodes, which own no sandbox the sweep may reclaim. */
  Set<String> pendingAgentNames() {
    return getPendingAgentNames();
  }

  private Set<String> getPendingAgentNames() {
    if (pendingAgentNames == null) {
      pendingAgentNames = ConcurrentHashMap.newKeySet();
    }
    return pendingAgentNames;
  }

  void markAgentRegistered(String agentName) {
    getPendingAgentNames().remove(agentName);
  }

  /**
   * Atomically reserves one slot for an exec-mode sandbox.
   *
   * <p>The reservation happens before the API call so concurrent Pipeline steps cannot all pass a
   * count check and then create beyond the exec sandbox cap. This cap is separate from agent
   * capacity so short exec-mode workloads cannot starve Jenkins agent provisioning.
   */
  synchronized boolean reserveExecSandbox(String sandboxName) {
    if (getActiveExecSandboxNames().contains(sandboxName)) {
      return true;
    }
    if (getActiveExecSandboxNames().size() >= getExecSandboxCap()) {
      return false;
    }
    getActiveExecSandboxNames().add(sandboxName);
    return true;
  }

  /** Restores an already-created exec sandbox to the in-memory set after Pipeline resume. */
  synchronized void restoreExecSandbox(String sandboxName) {
    if (sandboxName != null) {
      getActiveExecSandboxNames().add(sandboxName);
    }
  }

  /** Releases an exec-mode capacity reservation after cleanup or failed startup. */
  synchronized void releaseExecSandbox(String sandboxName) {
    if (sandboxName != null) {
      getActiveExecSandboxNames().remove(sandboxName);
    }
  }

  /** Controller-owned exec sandbox names that the orphan sweep must leave running. */
  Set<String> activeExecSandboxNames() {
    return Set.copyOf(getActiveExecSandboxNames());
  }

  private Set<String> getActiveExecSandboxNames() {
    if (activeExecSandboxNames == null) {
      activeExecSandboxNames = ConcurrentHashMap.newKeySet();
    }
    return activeExecSandboxNames;
  }

  /** Builds an authenticated API client from this cloud's configured Jenkins credential. */
  public CreateOSApiClient buildApiClient() {
    String apiKey = resolveApiKey();
    if (apiKey == null) {
      throw new IllegalStateException(
          "CreateOS API key not found for credential: " + credentialsId);
    }
    return new CreateOSApiClient(apiUrl, apiKey);
  }

  private String resolveApiKey() {
    StringCredentials cred =
        CredentialsMatchers.firstOrNull(
            CredentialsProvider.lookupCredentialsInItemGroup(
                StringCredentials.class, Jenkins.get(), ACL.SYSTEM2),
            CredentialsMatchers.withId(credentialsId));
    return cred != null ? cred.getSecret().getPlainText() : null;
  }

  // --- Getters and setters ---

  public String getApiUrl() {
    return apiUrl;
  }

  @Override
  public String getDisplayName() {
    return displayName == null || displayName.isBlank() ? name : displayName;
  }

  @DataBoundSetter
  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  @DataBoundSetter
  public void setApiUrl(String apiUrl) {
    this.apiUrl = apiUrl;
  }

  public String getCredentialsId() {
    return credentialsId;
  }

  @DataBoundSetter
  public void setCredentialsId(String credentialsId) {
    this.credentialsId = credentialsId;
  }

  public int getContainerCap() {
    return containerCap;
  }

  @DataBoundSetter
  public void setContainerCap(int containerCap) {
    this.containerCap = containerCap;
  }

  public int getExecSandboxCap() {
    return execSandboxCap > 0 ? execSandboxCap : DEFAULT_SANDBOX_CAP;
  }

  @DataBoundSetter
  public void setExecSandboxCap(int execSandboxCap) {
    this.execSandboxCap = execSandboxCap;
  }

  public List<SandboxTemplate> getTemplates() {
    return templates;
  }

  @DataBoundSetter
  public void setTemplates(List<SandboxTemplate> templates) {
    this.templates = templates != null ? templates : new ArrayList<>();
  }

  // --- Descriptor ---

  /** Exposes CreateOS cloud metadata, options, and validation to Jenkins. */
  @Extension
  public static class DescriptorImpl extends Descriptor<Cloud> {

    @Override
    public String getDisplayName() {
      return "CreateOS Sandbox";
    }

    /** Returns the secret-text credentials available to Jenkins administrators. */
    @POST
    public ListBoxModel doFillCredentialsIdItems() {
      if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
        return new StandardListBoxModel();
      }
      return new StandardListBoxModel()
          .includeEmptyValue()
          .includeMatchingAs(
              ACL.SYSTEM2,
              Jenkins.get(),
              StringCredentials.class,
              Collections.emptyList(),
              CredentialsMatchers.always());
    }

    /** Validates that the configured API endpoint is an HTTP URL. */
    @POST
    public FormValidation doCheckApiUrl(@QueryParameter String value) {
      Jenkins.get().checkPermission(Jenkins.ADMINISTER);
      if (value == null || value.isBlank()) {
        return FormValidation.error("API URL is required");
      }
      if (!value.startsWith("http://") && !value.startsWith("https://")) {
        return FormValidation.error("Must start with http:// or https://");
      }
      return FormValidation.ok();
    }

    /** Tests the configured credential against the CreateOS API. */
    @POST
    public FormValidation doTestConnection(
        @QueryParameter String apiUrl, @QueryParameter String credentialsId) {
      // Outside the try: an AccessDeniedException must propagate as a 403, not be caught
      // below and rendered back to the caller as an ordinary form-validation error.
      Jenkins.get().checkPermission(Jenkins.ADMINISTER);
      try {
        StringCredentials cred =
            CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                    StringCredentials.class, Jenkins.get(), ACL.SYSTEM2),
                CredentialsMatchers.withId(credentialsId));
        if (cred == null) {
          return FormValidation.error("Credential not found");
        }
        new CreateOSApiClient(apiUrl, cred.getSecret().getPlainText()).testConnection();
        return FormValidation.ok("Connection successful");
      } catch (Exception e) {
        return FormValidation.error("Failed: " + e.getMessage());
      }
    }
  }
}
