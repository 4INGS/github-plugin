package org.jenkinsci.plugins.github.webhook.subscriber;

import com.cloudbees.jenkins.GitHubPushTrigger;
import com.cloudbees.jenkins.GitHubRepositoryName;
import com.cloudbees.jenkins.GitHubRepositoryNameContributor;
import com.cloudbees.jenkins.GitHubTriggerEvent;
import com.cloudbees.jenkins.GitHubWebHook;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Item;
import hudson.security.ACL;
import java.io.IOException;
import java.io.StringReader;
import java.net.URL;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.github.extension.GHSubscriberEvent;
import org.jenkinsci.plugins.github.extension.GHEventsSubscriber;
import org.kohsuke.github.GHEvent;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GitHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

import static com.google.common.collect.Sets.immutableEnumSet;
import static org.jenkinsci.plugins.github.util.JobInfoHelpers.triggerFrom;
import static org.jenkinsci.plugins.github.util.JobInfoHelpers.withTrigger;
import static org.kohsuke.github.GHEvent.PUSH;

/**
 * By default this plugin interested in push events only when job uses {@link GitHubPushTrigger}
 *
 * @author lanwen (Merkushev Kirill)
 * @since 1.12.0
 */
@Extension
@SuppressWarnings("unused")
public class DefaultPushGHEventSubscriber extends GHEventsSubscriber {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultPushGHEventSubscriber.class);

    /**
     * This subscriber is applicable only for job with GHPush trigger
     *
     * @param project to check for trigger
     *
     * @return true if project has {@link GitHubPushTrigger}
     */
    @Override
    protected boolean isApplicable(Item project) {
        return withTrigger(GitHubPushTrigger.class).apply(project);
    }

    /**
     * @return set with only push event
     */
    @Override
    protected Set<GHEvent> events() {
        return immutableEnumSet(PUSH);
    }

    /**
     * Calls {@link GitHubPushTrigger} in all projects to handle this hook
     *
     * @param event   only PUSH event
     */
    @Override
    protected void onEvent(final GHSubscriberEvent event) {
        GHEventPayload.Push push;
        try {
            push = GitHub.offline().parseEventPayload(new StringReader(event.getPayload()), GHEventPayload.Push.class);
        } catch (IOException e) {
            LOGGER.warn("Received malformed PushEvent: " + event.getPayload(), e);
            return;
        }
        URL repoUrl = push.getRepository().getUrl();
        final String pusherName = push.getPusher().getName();
        final String pusherBranch = push.getRef();
        LOGGER.info("Received PushEvent for {} from {}", repoUrl, event.getOrigin());
        final GitHubRepositoryName changedRepository = GitHubRepositoryName.create(repoUrl.toExternalForm());

        if (changedRepository != null) {
            // run in high privilege to see all the projects anonymous users don't see.
            // this is safe because when we actually schedule a build, it's a build that can
            // happen at some random time anyway.
            ACL.impersonate(ACL.SYSTEM, new Runnable() {
                @Override
                public void run() {
                    for (Item job : Jenkins.getInstance().getAllItems(Item.class)) {
                        GitHubPushTrigger trigger = triggerFrom(job, GitHubPushTrigger.class);
                        if (trigger != null) {
                            String fullDisplayName = job.getFullDisplayName();
                            LOGGER.debug("Considering to poke {}", fullDisplayName);
                            if (GitHubRepositoryNameContributor.parseAssociatedNames(job)
                                    .contains(changedRepository)) {
                                if (isEligibleTrigger(job, pusherBranch, pusherName)) {
                                    LOGGER.info("Poked {}", fullDisplayName);
                                    trigger.onPost(GitHubTriggerEvent.create()
                                            .withTimestamp(event.getTimestamp())
                                            .withOrigin(event.getOrigin())
                                            .withTriggeredByUser(pusherName)
                                            .build()
                                    );
                                }
                            } else {
                                LOGGER.debug("Skipped {} because it doesn't have a matching repository.",
                                        fullDisplayName);
                            }
                        }
                    }
                }
            });

            for (GitHubWebHook.Listener listener : ExtensionList.lookup(GitHubWebHook.Listener.class)) {
                listener.onPushRepositoryChanged(pusherName, changedRepository);
            }

        } else {
            LOGGER.warn("Malformed repo url {}", repoUrl);
        }
    }

    private boolean isEligibleTrigger(
            Item job, String pusherBranch, String pusherName) {
        Set ignoredUsers = new java.util.HashSet();
        boolean eligibleBranch = false;

        if (job instanceof hudson.model.Project) {
            hudson.scm.SCM scm = ((hudson.model.Project) job).getScm();
            if (scm instanceof hudson.plugins.git.GitSCM) {
                hudson.plugins.git.extensions.impl.UserExclusion userExclusions =
                    ((hudson.plugins.git.GitSCM) scm)
                        .getExtensions()
                        .get(hudson.plugins.git.extensions.impl.UserExclusion.class);
                if (userExclusions != null) {
                    ignoredUsers = userExclusions.getExcludedUsersNormalized();
                }

                java.util.List<hudson.plugins.git.BranchSpec> branches =
                        ((hudson.plugins.git.GitSCM) scm).getBranches();
                LOGGER.info("branches: {}", branches);
                for (hudson.plugins.git.BranchSpec branch : branches) {
                    if (pusherBranch.equals(branch.getName())) {
                        eligibleBranch = true;
                    }
                }
            }
        }

        // This needs to be able to process Pipeline jobs as well as Freestyle jobs.
        // The above block (hudson.model.Project) doesn't grab Pipeline jobs.
        // Since we can't (yet) easily grab the scm(s) from a Pipeline job, just
        // assume it will be handled correctly (for now).
        // We just need to keep an eye on this in case this causes any unexpected fallout.
        if (job instanceof org.jenkinsci.plugins.workflow.job.WorkflowJob) {
            eligibleBranch = true;
        }

        LOGGER.info("ignoredUsers: {}", ignoredUsers);
        LOGGER.info("eligibleBranch: {}", eligibleBranch);

        return (!ignoredUsers.contains(pusherName) && eligibleBranch);
    }
}
