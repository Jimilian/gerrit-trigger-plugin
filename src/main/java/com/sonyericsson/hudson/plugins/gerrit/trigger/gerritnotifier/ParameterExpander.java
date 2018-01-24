/*
 *  The MIT License
 *
 *  Copyright 2010 Sony Ericsson Mobile Communications. All rights reserved.
 *  Copyright 2012 Sony Mobile Communications AB. All rights reserved.
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */
package com.sonyericsson.hudson.plugins.gerrit.trigger.gerritnotifier;


import com.sonyericsson.hudson.plugins.gerrit.trigger.config.Config;
import com.sonyericsson.hudson.plugins.gerrit.trigger.config.IGerritHudsonTriggerConfig;
import com.sonyericsson.hudson.plugins.gerrit.trigger.gerritnotifier.model.BuildMemory.MemoryImprint;
import com.sonyericsson.hudson.plugins.gerrit.trigger.gerritnotifier.model.BuildMemory.MemoryImprint.Entry;
import com.sonyericsson.hudson.plugins.gerrit.trigger.gerritnotifier.model.BuildsStartedStats;
import com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.GerritTrigger;
import com.sonyericsson.hudson.plugins.gerrit.trigger.utils.StringUtil;
import com.sonymobile.tools.gerrit.gerritevents.GerritQueryHandler;
import com.sonymobile.tools.gerrit.gerritevents.dto.attr.Change;
import com.sonymobile.tools.gerrit.gerritevents.dto.attr.PatchSet;
import com.sonymobile.tools.gerrit.gerritevents.dto.events.ChangeBasedEvent;
import com.sonymobile.tools.gerrit.gerritevents.dto.events.GerritTriggeredEvent;
import com.sonymobile.tools.gerrit.gerritevents.dto.rest.Notify;

import com.sonymobile.tools.gerrit.gerritevents.dto.rest.Topic;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jenkins.model.Jenkins;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.sonyericsson.hudson.plugins.gerrit.trigger.PluginImpl.getServerConfig;
import static com.sonyericsson.hudson.plugins.gerrit.trigger.utils.Logic.shouldSkip;

/**
 * Expands a parameterized string to its full potential.
 *
 * @author Robert Sandell &lt;robert.sandell@sonyericsson.com&gt;
 */
public class ParameterExpander {

    /**
     * How many default parameters there are (plus one) to initialize the size of the parameters-map.
     */
    public static final int DEFAULT_PARAMETERS_COUNT = 11;

    /**
     * The delimiter used to separate build URLs from their messages.
     */
    public static final String MESSAGE_DELIMITER = " : ";

    private static final Logger logger = LoggerFactory.getLogger(ParameterExpander.class);
    private IGerritHudsonTriggerConfig config;
    private Jenkins jenkins;

    /**
     * Constructor.
     * @param config the global config.
     * @param jenkins the Hudson instance.
     */
    public ParameterExpander(IGerritHudsonTriggerConfig config, Jenkins jenkins) {
        this.config = config;
        this.jenkins = jenkins;
    }

    /**
     * Constructor.
     * @param config the global config.
     */
    public ParameterExpander(IGerritHudsonTriggerConfig config) {
        this(config, Jenkins.getInstance());
    }

    /**
     * Gets the expanded string to send to Gerrit for a build-started event.
     * @param r the build.
     * @param taskListener the taskListener.
     * @param event the event.
     * @param stats the statistics.
     * @return the "expanded" command string.
     */
    public String getBuildStartedCommand(Run r, TaskListener taskListener,
            ChangeBasedEvent event, BuildsStartedStats stats) {

        GerritTrigger trigger = GerritTrigger.getTrigger(r.getParent());
        String gerritCmd = config.getGerritCmdBuildStarted();
        Map<String, String> parameters = createStandardParameters(r,
                getBuildStartedCodeReviewValue(r),
                getBuildStartedVerifiedValue(r),
                Notify.ALL.name());
        StringBuilder startedStats = new StringBuilder();
        if (stats.getTotalBuildsToStart() > 1) {
            startedStats.append(stats.toString());
        }
        String buildStartMessage = trigger.getBuildStartMessage();
        if (buildStartMessage != null && !buildStartMessage.isEmpty()) {
            startedStats.append("\n\n").append(expandParameters(buildStartMessage, r, taskListener, parameters));
        }

        if (config.isEnablePluginMessages()) {
            for (GerritMessageProvider messageProvider : emptyIfNull(GerritMessageProvider.all())) {
                String extensionMessage = messageProvider.getBuildStartedMessage(r);
                if (extensionMessage != null) {
                    startedStats.append("\n\n").append(extensionMessage);
                }
            }
        }

        addChangeBasedData(event.getChange(), event.getPatchSet(), parameters);
        parameters.put("STARTED_STATS", startedStats.toString());

        return expandParameters(gerritCmd, r, taskListener, parameters);
    }

    /**
     * Gets the expanded string to send to Gerrit for a build-started event.
     * @param runs the builds.
     * @param taskListener the taskListener.
     * @param event the event.
     * @param stats the statistics.
     * @return the "expanded" command string.
     */
    public List<String> getBuildsStartedCommand(List<Run> runs, TaskListener taskListener,
                                         ChangeBasedEvent event, BuildsStartedStats stats) {

        String gerritCmd = config.getGerritCmdBuildStarted();

        GerritQueryHandler queryHandler = new GerritQueryHandler(getServerConfig(event));

        Topic topic = event.getChange().getTopicObject();
        Map<Change, PatchSet> topicChanges;
        if (topic != null) {
            topicChanges = topic.getChanges(queryHandler);
        } else {
            topicChanges = Collections.singletonMap(event.getChange(), event.getPatchSet());
        }

        List<String> commands = new ArrayList<String>();
        for (Map.Entry<Change, PatchSet> changePatchSetEntry : topicChanges.entrySet()) {
            Integer minCodeReview = Integer.MAX_VALUE;
            Integer minVerified = Integer.MAX_VALUE;

            StringBuilder aggregatedBuilds = new StringBuilder();

            int offset = runs.size() - 1;

            for (Run r : runs) {
                GerritTrigger trigger = GerritTrigger.getTrigger(r.getParent());

                if (!trigger.isInterestingChange(changePatchSetEntry.getKey(), queryHandler)) {
                    continue;
                }

                minCodeReview = Math.min(minCodeReview, getBuildStartedCodeReviewValue(r));
                minVerified = Math.min(minVerified, getBuildStartedVerifiedValue(r));

                aggregatedBuilds.append("\n\n");

                String buildStartMessage = trigger.getBuildStartMessage();
                if (buildStartMessage != null && !buildStartMessage.isEmpty()) {
                    aggregatedBuilds.append("\n\n").append(buildStartMessage);
                }

                String buildUrl = jenkins.getRootUrl() + r.getUrl();
                aggregatedBuilds.append(buildUrl);

                if (stats.getTotalBuildsToStart() > 1) {
                    aggregatedBuilds.append(" ");
                    aggregatedBuilds.append(stats.stringWithOffset(offset));
                    aggregatedBuilds.append(" ");
                    offset--;
                }

                if (config.isEnablePluginMessages()) {
                    for (GerritMessageProvider messageProvider : emptyIfNull(GerritMessageProvider.all())) {
                        String extensionMessage = messageProvider.getBuildStartedMessage(r);
                        if (extensionMessage != null) {
                            aggregatedBuilds.append("\n\n").append(extensionMessage);
                        }
                    }
                }
            }

            if (aggregatedBuilds.length() != 0) {
                Map<String, String> parameters = createStandardParameters(null,
                        minCodeReview,
                        minVerified,
                        Notify.ALL.name());

                parameters.put("BUILDURL", "");
                parameters.put("STARTED_STATS", aggregatedBuilds.toString());
                addChangeBasedData(changePatchSetEntry.getKey(), changePatchSetEntry.getValue(), parameters);

                commands.add(expandParameters(gerritCmd, runs.get(0), taskListener, parameters));
            }
        }

        return commands;
    }

    /**
     * Helper for ensuring no NPEs when iterating iterables.
     *
     * @param <T> type
     * @param iterable the iterable
     * @return empty if null or the iterable
     */
    private static <T> Iterable<T> emptyIfNull(Iterable<T> iterable) {
        if (iterable == null) {
            return Collections.<T>emptyList();
        } else {
            return iterable;
        }
    }

    /**
     * Finds the verified vote for build started of the specified build.
     * If there is a {@link GerritTrigger} and it has a {@link GerritTrigger#getGerritBuildStartedVerifiedValue()}
     * specified, that value will be used, otherwise the global config value in
     * {@link IGerritHudsonTriggerConfig#getGerritBuildStartedVerifiedValue()} will be used.
     * @param r the build.
     * @return the value.
     */
    private Integer getBuildStartedVerifiedValue(Run r) {
        GerritTrigger trigger = GerritTrigger.getTrigger(r.getParent());
        if (trigger == null) {
            logger.warn("Unable to get trigger config for build {} will use global value.");
            return config.getGerritBuildStartedVerifiedValue();
        } else if (trigger.getGerritBuildStartedVerifiedValue() != null) {
            final Integer value = trigger.getGerritBuildStartedVerifiedValue();
            logger.trace("BuildStartedVerified overridden in project config. returning {}", value);
            return value;
        } else {
            final Integer value = config.getGerritBuildStartedVerifiedValue();
            logger.trace("BuildStartedVerified standard value used {}", value);
            return value;
        }
    }

    /**
     * Finds the code review vote for build started of the specified build.
     * If there is a {@link GerritTrigger} and it has a {@link GerritTrigger#getGerritBuildStartedCodeReviewValue()}
     * specified, that value will be used, otherwise the global config value in
     * {@link IGerritHudsonTriggerConfig#getGerritBuildStartedCodeReviewValue()} will be used.
     * @param r the build.
     * @return the value.
     */
    private Integer getBuildStartedCodeReviewValue(Run r) {
        GerritTrigger trigger = GerritTrigger.getTrigger(r.getParent());
        if (trigger == null) {
            logger.warn("Unable to get trigger config for build {} will use global value.");
            return config.getGerritBuildStartedCodeReviewValue();
        } else if (trigger.getGerritBuildStartedCodeReviewValue() != null) {
            final Integer value = trigger.getGerritBuildStartedCodeReviewValue();
            logger.trace("BuildStartedCodeReview overridden in project config. returning {}", value);
            return value;
        } else {
            final Integer value = config.getGerritBuildStartedCodeReviewValue();
            logger.trace("BuildStartedCodeReview standard value used {}", value);
            return value;
        }
    }

    /**
     * Creates a list of the "standard" trigger parameters.
     * They are present both for build started and completed.
     * The parameters are:
     * <ul>
     *  <li><strong>GERRIT_NAME</strong>: The Gerrit project name.</li>
     *  <li><strong>CHANGE_ID</strong>: The Gerrit change-id (SHA-1).</li>
     *  <li><strong>BRANCH</strong>: The branch of the project.</li>
     *  <li><strong>TOPIC</strong>: Topic name for change series.</li>
     *  <li><strong>CHANGE</strong>: The change number.</li>
     *  <li><strong>PATCHSET</strong>: The patchset number.</li>
     *  <li><strong>PATCHSET_REVISION</strong>: The patchset revision.</li>
     *  <li><strong>REFSPEC</strong>: The ref-spec. (refs/changes/xx/xxxx/z).</li>
     *  <li><strong>BUILDURL</strong>: The URL to the build.</li>
     *  <li><strong>VERIFIED</strong>: The verified vote.</li>
     *  <li><strong>CODE_REVIEW</strong>: The code review vote.</li>
     *  <li><strong>NOTIFICATION_LEVEL</strong>: The notification level.</li>
     * </ul>
     * @param r the build.
     * @param codeReview the code review vote.
     * @param verified the verified vote.
     * @param notifyLevel the notify level.
     * @return the parameters and their values.
     */
    private Map<String, String> createStandardParameters(Run r,
                                                         Integer codeReview,
                                                         Integer verified,
                                                         String notifyLevel) {
        //<GERRIT_NAME> <BRANCH> <CHANGE> <PATCHSET> <PATCHSET_REVISION> <REFSPEC> <BUILDURL> VERIFIED CODE_REVIEW
        Map<String, String> map = new HashMap<String, String>(DEFAULT_PARAMETERS_COUNT);

        if (r != null) {
            map.put("BUILDURL", jenkins.getRootUrl() + r.getUrl());
        }

        map.put("VERIFIED", String.valueOf(verified));
        map.put("CODE_REVIEW", String.valueOf(codeReview));
        map.put("NOTIFICATION_LEVEL", notifyLevel);

        return map;
    }

    /**
     * Add change related info to parameters
     * @param change the change.
     * @param patchSet the patchset.
     * @param parameters the input parameters
     */
    private void addChangeBasedData(Change change, PatchSet patchSet, Map<String, String> parameters) {
        parameters.put("GERRIT_NAME", change.getProject());
        parameters.put("CHANGE_ID", change.getId());
        parameters.put("BRANCH", change.getBranch());
        if (null != change.getTopic()) {
            parameters.put("TOPIC", change.getTopic());
        }
        parameters.put("CHANGE", change.getNumber());
        if (null != patchSet) {
            parameters.put("PATCHSET", patchSet.getNumber());
            parameters.put("PATCHSET_REVISION", patchSet.getRevision());
            parameters.put("REFSPEC", StringUtil.makeRefSpec(change, patchSet));
        }
    }

    /**
     * Expands all types of parameters in the string and returns the "replaced" string.
     * Both types means both $ENV_VARS and &lt;PLUGIN_VARS&gt;
     * @param gerritCommand the command "template"
     * @param r the build containing the environment vars.
     * @param taskListener the taskListener
     * @param parameters the &lt;parameters&gt; from the trigger.
     * @return the expanded string.
     */
    private String expandParameters(String gerritCommand, Run r, TaskListener taskListener,
            Map<String, String> parameters) {
        String command = gerritCommand;
        if (r != null && taskListener != null) {
            try {
                command = r.getEnvironment(taskListener).expand(command);
            } catch (Exception ex) {
                logger.error("Failed to expand env vars into gerrit cmd. Gerrit won't be notified!!", ex);
                return null;
            }
        }

        for (Map.Entry<String, String> param : parameters.entrySet()) {
            command = command.replace("<" + param.getKey() + ">", param.getValue());
        }
        //replace null and Integer.MAX_VALUE code review value
        command = command.replace("--code-review null", "");
        command = command.replace("--code-review " + Integer.MAX_VALUE, "");
        command = command.replace("--verified null", "");
        command = command.replace("--verified " + Integer.MAX_VALUE, "");

        return command;
    }

    /**
     * Finds the code review value for the specified build result on the configured trigger.
     * @param res the build result.
     * @param trigger the trigger that might have overridden values.
     * @return the value.
     */
    protected Integer getCodeReviewValue(Result res, GerritTrigger trigger) {
        if (res == Result.SUCCESS) {
            if (trigger.getGerritBuildSuccessfulCodeReviewValue() != null) {
                return trigger.getGerritBuildSuccessfulCodeReviewValue();
            } else {
                return config.getGerritBuildSuccessfulCodeReviewValue();
            }
        } else if (res == Result.FAILURE || res == Result.ABORTED) {
            if (trigger.getGerritBuildFailedCodeReviewValue() != null) {
                return trigger.getGerritBuildFailedCodeReviewValue();
            } else {
                return config.getGerritBuildFailedCodeReviewValue();
            }
        } else if (res == Result.UNSTABLE) {
            if (trigger.getGerritBuildUnstableCodeReviewValue() != null) {
                return trigger.getGerritBuildUnstableCodeReviewValue();
            } else {
                return config.getGerritBuildUnstableCodeReviewValue();
            }
        } else if (res == Result.NOT_BUILT) {
            if (trigger.getGerritBuildNotBuiltCodeReviewValue() != null) {
                return trigger.getGerritBuildNotBuiltCodeReviewValue();
            } else {
                return config.getGerritBuildNotBuiltCodeReviewValue();
            }
        } else {
            //As bad as failue, for now
            if (trigger.getGerritBuildFailedCodeReviewValue() != null) {
                return trigger.getGerritBuildFailedCodeReviewValue();
            } else {
                return config.getGerritBuildFailedCodeReviewValue();
            }
        }
    }

    /**
     * Finds the verified value for the specified build result on the configured trigger.
     * @param res the build result.
     * @param trigger the trigger that might have overridden values.
     * @return the value.
     */
    protected Integer getVerifiedValue(Result res, GerritTrigger trigger) {
        if (res == Result.SUCCESS) {
            if (trigger.getGerritBuildSuccessfulVerifiedValue() != null) {
                return trigger.getGerritBuildSuccessfulVerifiedValue();
            } else {
                return config.getGerritBuildSuccessfulVerifiedValue();
            }
        } else if (res == Result.FAILURE || res == Result.ABORTED) {
            if (trigger.getGerritBuildFailedVerifiedValue() != null) {
                return trigger.getGerritBuildFailedVerifiedValue();
            } else {
                return config.getGerritBuildFailedVerifiedValue();
            }
        } else if (res == Result.UNSTABLE) {
            if (trigger.getGerritBuildUnstableVerifiedValue() != null) {
                return trigger.getGerritBuildUnstableVerifiedValue();
            } else {
                return config.getGerritBuildUnstableVerifiedValue();
            }
        } else if (res == Result.NOT_BUILT) {
            if (trigger.getGerritBuildNotBuiltVerifiedValue() != null) {
                return trigger.getGerritBuildNotBuiltVerifiedValue();
            } else {
                return config.getGerritBuildNotBuiltVerifiedValue();
            }
        } else {
            //As bad as failure, for now
            if (trigger.getGerritBuildFailedVerifiedValue() != null) {
                return trigger.getGerritBuildFailedVerifiedValue();
            } else {
                return config.getGerritBuildFailedVerifiedValue();
            }
        }
    }

    /**
     * Returns the minimum verified value for the build results in the memory.
     * If no builds have contributed to verified value, this method returns null
     * @param entries the memory entries.
     * @param onlyBuilt only count builds that completed (no NOT_BUILT builds)
     * @return the lowest verified value.
     */
    @CheckForNull
    public Integer getMinimumVerifiedValue(Entry[] entries, boolean onlyBuilt) {
        Integer verified = Integer.MAX_VALUE;
        for (Entry entry : entries) {
            if (entry == null) {
                continue;
            }
            Run build = entry.getBuild();
            if (build == null) {
                continue;
            }
            Result result = build.getResult();
            if (onlyBuilt && result == Result.NOT_BUILT) {
                continue;
            }

            GerritTrigger trigger = GerritTrigger.getTrigger(entry.getProject());
            if (shouldSkip(trigger.getSkipVote(), result)) {
                continue;
            }
            Integer verifiedObj = getVerifiedValue(result, trigger);
            if (verifiedObj != null) {
                verified = Math.min(verified, verifiedObj);
            }
        }

        if (verified == Integer.MAX_VALUE) {
            return null;
        }

        return verified;
    }

    /**
     * Convert entries of memoryImprint object to list of builds
     *
     * @param entries the entries from memoryImprint
     * @return the list of run objects from memory
     */
    private List<Run> fromMemoryImprintToBuilds(Entry[] entries) {
        final List<Run> runs = new ArrayList<Run>(entries.length);
        for (Entry entry : entries) {
            if (entry == null || entry.getBuild() == null) {
                continue;
            }
            runs.add(entry.getBuild());
        }

        return runs;
    }

    /**
     * Returns the minimum code review value for the build results in the memory.
     * If no builds have contributed to code review value, this method returns null
     * @param entries the memory entries
     * @param onlyBuilt only count builds that completed (no NOT_BUILT builds)
     * @return the lowest code review value.
     */
    @CheckForNull
    public Integer getMinimumCodeReviewValue(Entry[] entries, boolean onlyBuilt) {
        Integer codeReview = Integer.MAX_VALUE;
        for (Entry entry : entries) {
            Run build = entry.getBuild();
            if (build == null) {
                continue;
            }
            Result result = build.getResult();
            if (onlyBuilt && result == Result.NOT_BUILT) {
                continue;
            }

            GerritTrigger trigger = GerritTrigger.getTrigger(entry.getProject());
            if (shouldSkip(trigger.getSkipVote(), result)) {
                continue;
            }
            Integer codeReviewObj = getCodeReviewValue(result, trigger);
            if (codeReviewObj != null) {
                codeReview = Math.min(codeReview, codeReviewObj);
            }
        }

        if (codeReview == Integer.MAX_VALUE) {
            return null;
        }

        return codeReview;
    }

    /**
     * Returns the highest configured notification level.
     *
     * @param memoryImprint the memory
     * @param onlyBuilt only count builds that completed (no NOT_BUILT builds)
     * @return the highest configured notification level.
     */
    public Notify getHighestNotificationLevel(MemoryImprint memoryImprint, boolean onlyBuilt) {
        return getHighestNotificationLevel(fromMemoryImprintToBuilds(memoryImprint.getEntries()), onlyBuilt);
    }

    /**
     * Returns the highest configured notification level.
     *
     * @param builds the list of builds
     * @param onlyBuilt only count builds that completed (no NOT_BUILT builds)
     * @return the highest configured notification level.
     */
    public Notify getHighestNotificationLevel(List<Run> builds, boolean onlyBuilt) {
        Notify highestLevel = Notify.NONE;

        for (Run build : builds) {
            if (build == null) {
                continue;
            }

            Result result = build.getResult();
            if (onlyBuilt && result == Result.NOT_BUILT) {
                continue;
            }

            GerritTrigger trigger = GerritTrigger.getTrigger(build.getParent());
            if (trigger == null || shouldSkip(trigger.getSkipVote(), result)) {
                continue;
            }

            Notify level = getNotificationLevel(trigger);
            if (level != null && level.compareTo(highestLevel) > 0) {
                highestLevel = level;
            }
        }

        return highestLevel;
    }

    /**
     * Returns the notification level value for the given trigger.
     *
     * @param trigger the trigger.
     * @return the level value.
     */
    public Notify getNotificationLevel(GerritTrigger trigger) {
        String level = trigger.getNotificationLevel();
        if (level != null && level.length() > 0) {
            return Notify.valueOf(level);
        }
        Notify serverLevel = config.getNotificationLevel();
        if (serverLevel != null) {
            return serverLevel;
        }
        return Config.DEFAULT_NOTIFICATION_LEVEL;
    }

    /**
     * Gets the list of commands to be executed.
     *
     * @param memoryImprint the memory imprint.
     * @param listener the listener.
     * @return list of commands to be executed.
     */
    public List<String> getBuildCompletedCommands(MemoryImprint memoryImprint, TaskListener listener) {
        String command;
        // We only count builds without NOT_BUILT status normally. If *no*
        // builds were successful, unstable or failed, we find the minimum
        // verified/code review value for the NOT_BUILT ones too.
        boolean onlyCountBuilt = true;
        Result worstResult = memoryImprint.getWorstResult();
        if (worstResult.equals(Result.SUCCESS)) {
            command = config.getGerritCmdBuildSuccessful();
        } else if (worstResult.equals(Result.UNSTABLE)) {
            command = config.getGerritCmdBuildUnstable();
        } else if (worstResult.equals(Result.FAILURE)) {
            command = config.getGerritCmdBuildFailed();
        } else if (worstResult.equals(Result.NOT_BUILT)) {
            onlyCountBuilt = false;
            command = config.getGerritCmdBuildNotBuilt();
        } else {
            //Just as bad as failed for now.
            command = config.getGerritCmdBuildFailed();
        }

        Integer verified = null;
        Integer codeReview = null;
        Notify notifyLevel = Notify.ALL;
        GerritTriggeredEvent gerritEvent = memoryImprint.getEvent();

        Entry[] entries = memoryImprint.getEntries();

        if (gerritEvent.isScorable()) {
            verified = getMinimumVerifiedValue(entries, onlyCountBuilt);
            codeReview = getMinimumCodeReviewValue(entries, onlyCountBuilt);
            notifyLevel = getHighestNotificationLevel(fromMemoryImprintToBuilds(entries), onlyCountBuilt);
        }

        Map<String, String> parameters = createStandardParameters(null, codeReview, verified, notifyLevel.name());

        if (gerritEvent instanceof ChangeBasedEvent) {
            return fillOnCompletedParams(memoryImprint, listener, command, parameters);
        } else {
            return Collections.singletonList(getBuildCompletedCommand(command,
                    entries,
                    listener,
                    parameters));
        }
    }

    /**
     * Fill the params.
     *
     * @param memoryImprint x.
     * @param listener x.
     * @param command x.
     * @param parameters x.
     * @return x.
     */
    private List<String> fillOnCompletedParams(MemoryImprint memoryImprint,
                                               TaskListener listener, String command, Map<String, String> parameters) {
        ChangeBasedEvent changeBasedEvent = (ChangeBasedEvent)memoryImprint.getEvent();

        Topic topic = changeBasedEvent.getChange().getTopicObject();
        if (topic != null) {
            List<String> commands = new ArrayList<String>();
            GerritQueryHandler queryHandler = new GerritQueryHandler(getServerConfig(changeBasedEvent));

            for (Map.Entry<Change, PatchSet> pair : topic.getChanges(queryHandler).entrySet()) {
                Change change = pair.getKey();
                List<Entry> newEntries = filterInterestingEntries(memoryImprint.getEntries(), change, queryHandler);

                if (!newEntries.isEmpty()) {
                    PatchSet patchSet = pair.getValue();
                    addChangeBasedData(change, patchSet, parameters);
                    String buildCompletedCommand = getBuildCompletedCommand(command,
                            newEntries.toArray(new Entry[newEntries.size()]), listener, parameters);
                    commands.add(buildCompletedCommand);
                }
            }
            return commands;
        } else {
            addChangeBasedData(changeBasedEvent.getChange(), changeBasedEvent.getPatchSet(), parameters);
            return Collections.singletonList(getBuildCompletedCommand(command,
                    memoryImprint.getEntries(),
                    listener,
                    parameters));
        }
    }

        /**
         * Filter out.
         * @param entries x.
         * @param change x.
         * @param gerritQueryHandler x.
         * @return x.
         */
    private List<Entry> filterInterestingEntries(Entry[] entries, Change change, GerritQueryHandler gerritQueryHandler) {
        List<Entry> entryList = Arrays.asList(entries);
        List<Entry> newEntries = new ArrayList<Entry>();
        for (Entry entry : entryList) {
            Job project = entry.getProject();
            if (isInterestingProject(change, project, gerritQueryHandler)) {
                newEntries.add(entry);
            }
        }
        return newEntries;
    }

    /**
     * xxx.
     * @param change x.
     * @param project x.
     * @param gerritQueryHandler x.
     * @return x.
     */
    private boolean isInterestingProject(Change change, Job project, GerritQueryHandler gerritQueryHandler) {
        GerritTrigger gerritTrigger = GerritTrigger.getTrigger(project);
        if (gerritTrigger == null) {
            logger.info("Skip " + project + ", because trigger is null");
            return false;
        }

        return gerritTrigger.isInterestingChange(change, gerritQueryHandler);
    }

    /**
     * Gets the "expanded" build completed command to send to gerrit.
     *
     * @param command the command to be expanded.
     * @param entries the entries.
     * @param listener      the taskListener
     * @param parameters the map of build parameteres.
     * @return the command.
     */
    public String getBuildCompletedCommand(String command,
                                           Entry[] entries, TaskListener listener, Map<String, String> parameters) {
        // escapes ' as '"'"' in order to avoid breaking command line param
        // Details: http://stackoverflow.com/a/26165123/99834
        parameters.put("BUILDS_STATS", createBuildsStats(entries,
                                                         listener,
                                                         parameters).replaceAll("'", "'\"'\"'"));

        Run build = null;
        if (entries.length > 0 && entries[0].getBuild() != null) {
            build = entries[0].getBuild();
        }

        return expandParameters(command, build, listener, parameters);
    }

    /**
     * Creates the BUILD_STATS string to send in a message,
     * it contains the status of every build with its URL.
     * @param entries the memory entries for all the builds.
     * @param listener the taskListener
     * @param parameters the &lt;parameters&gt; from the trigger.
     * @return the string.
     */
    private String createBuildsStats(Entry[] entries, TaskListener listener,
            Map<String, String> parameters) {
        StringBuilder str = new StringBuilder("");
        final String rootUrl = jenkins.getRootUrl();

        String unsuccessfulMessage = null;

        /* Sort entries with worst results first so the attention is drawn on them.
         * Otherwise users may e.g. miss an UNSTABLE result because they see SUCCESS first.
         */
        Arrays.sort(entries, EntryByBuildResultComparator.DESCENDING);

        // In Gerrit, all lines before the first empty line are used as the summary.
        // For the summary all single linefeeds will be removed (only in Gerrit, not sent mails).
        // Hence, for the multi-builds, we will add a double linefeed before actually listing
        // the build results.
        if (entries.length > 0) {
            for (Entry entry : entries) {
                if (entry == null) {
                    continue;
                }
                Run build = entry.getBuild();
                if (build != null) {
                    GerritTrigger trigger = GerritTrigger.getTrigger(build.getParent());
                    Result res = build.getResult();
                    if (res == null) {
                        res = Result.NOT_BUILT;
                    }
                    /* Gerrit comments cannot contain single-newlines, as they will be joined
                     * together. Double newlines are interpreted as paragraph breaks. Lines that
                     * begin with a space (even if the space occurs somewhere in the middle of
                     * a multi-line paragraph) are interpreted as code blocks.
                     */
                    str.append("\n\n");

                    if (entry.getCustomUrl() != null && !entry.getCustomUrl().isEmpty()) {
                        str.append(expandParameters(entry.getCustomUrl(), build, listener, parameters));
                    } else if (trigger.getCustomUrl() != null && !trigger.getCustomUrl().isEmpty()) {
                        str.append(expandParameters(trigger.getCustomUrl(), build, listener, parameters));
                    } else {
                        str.append(rootUrl).append(build.getUrl());
                    }
                    str.append(MESSAGE_DELIMITER);

                    String customMessage = null;
                    if (res == Result.SUCCESS) {
                        customMessage = trigger.getBuildSuccessfulMessage();
                    } else if (res == Result.FAILURE || res == Result.ABORTED) {
                        customMessage = trigger.getBuildFailureMessage();
                    } else if (res == Result.UNSTABLE) {
                        customMessage = trigger.getBuildUnstableMessage();
                    } else if (res == Result.NOT_BUILT) {
                        customMessage = trigger.getBuildNotBuiltMessage();
                    } else {
                        customMessage = trigger.getBuildFailureMessage();
                    }

                    // If the user has specified a message, use it
                    // otherwise use a generic indicator
                    if (customMessage == null || customMessage.isEmpty()) {
                        str.append(res.toString());
                        if (shouldSkip(trigger.getSkipVote(), res)) {
                            str.append(" (skipped)");
                        }
                    } else {
                        str.append(expandParameters(customMessage, build, listener, parameters));
                    }

                    if (res.isWorseThan(Result.SUCCESS)) {
                        unsuccessfulMessage = entry.getUnsuccessfulMessage();

                        if (null != unsuccessfulMessage && !unsuccessfulMessage.isEmpty()) {
                            logger.trace("Using unsuccessful message.");
                            str.append(" <<<\n");
                            str.append(unsuccessfulMessage.trim());
                            str.append("\n>>>");
                        }
                    }

                    if (config.isEnablePluginMessages()) {
                        for (GerritMessageProvider messageProvider : emptyIfNull(GerritMessageProvider.all())) {
                            String extensionMessage = messageProvider.getBuildCompletedMessage(build);
                            if (extensionMessage != null) {
                                str.append("\n\n").append(extensionMessage);
                            }
                        }
                    }
                }
            }
        } else {
            logger.error("I got a request to create build statistics, but no entries where found!");
        }

        return str.toString();
    }

    /**
     * Returns cover message to be send after build has been completed.
     *
     * @param memoryImprint memory
     * @param listener listener
     * @return the message for the build completed command.
     */
    public List<String> getBuildCompletedMessage(MemoryImprint memoryImprint, TaskListener listener) {
        List<String> completedCommands = getBuildCompletedCommands(memoryImprint, listener);
        return findMessage(completedCommands);
    }

    /**
     * Returns cover message to be send after build has been started.
     *
     * @param builds build
     * @param listener listener
     * @param event event
     * @param stats stats
     * @return the message for the build started command.
     */
    public List<String> getBuildsStartedMessage(List<Run> builds, TaskListener listener, ChangeBasedEvent event,
                                          BuildsStartedStats stats) {
//        String startedCommand;
//        if (builds.size() == 1) {
//            startedCommand = getBuildStartedCommand(builds.get(0), listener, event, stats);
//        } else {
//            startedCommand = ;
//        }

        return findMessage(getBuildsStartedCommand(builds, listener, event, stats));
    }

    /**
     * Finds the --message part of the command.
     * TODO Solve it in a better way
     *
     * @param completedCommands the command
     * @return the message
     */
    protected List<String> findMessage(List<String> completedCommands) {
        String messageStart = "--message '";
        List<String> messages = new ArrayList<String>(completedCommands.size());
        for (String command : completedCommands) {
            String fromMessage = command.substring(command.indexOf(messageStart));
            int endIndex = fromMessage.indexOf("' --");
            if (endIndex <= -1) {
                endIndex = fromMessage.length();
            }
            messages.add(fromMessage.substring(messageStart.length(), endIndex));
        }
        return messages;
    }

    /**
     * Sorts build entries along their results.
     */
    private static final class EntryByBuildResultComparator implements Comparator<Entry> {
        /**
         * Sorts with worse results first.
         */
        static final EntryByBuildResultComparator DESCENDING = new EntryByBuildResultComparator(true);

        private boolean descending;
        /**
         * Creates a comparator
         *
         * @param descending <code>true</code> for worst results first,
         *         <code>false</code> for best results first
         */
        private EntryByBuildResultComparator(boolean descending) {
            this.descending = descending;
        }

        @Override
        public int compare(Entry e1, Entry e2) {
            if (e1 == null) {
                throw new NullPointerException("e1");
            }
            if (e2 == null) {
                throw new NullPointerException("e2");
            }
            Run b1 = e1.getBuild();
            Run b2 = e2.getBuild();
            if (b1 != null && b2 != null) {
                Result r1 = b1.getResult();
                Result r2 = b2.getResult();
                int o1 = 0;
                if (r1 != null) {
                    o1 = r1.ordinal;
                }
                int o2 = 0;
                if (r2 != null) {
                    o2 = r2.ordinal;
                }

                if (r1 == Result.ABORTED) {
                    o1 = -1;
                }

                if (r2 == Result.ABORTED) {
                    o2 = -1;
                }

                if (descending) {
                    return o2 - o1;
                } else {
                    return o1 - o2;
                }
            } else if (b1 != null) {
                return 1;
            } else if (b2 != null) {
                return -1;
            }
            return 0;
        }
    }

}
