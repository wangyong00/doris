// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.common.profile;

import org.apache.doris.common.Pair;
import org.apache.doris.common.Status;
import org.apache.doris.common.util.DebugUtil;
import org.apache.doris.planner.PlanFragmentId;
import org.apache.doris.thrift.TDetailedReportParams;
import org.apache.doris.thrift.TNetworkAddress;
import org.apache.doris.thrift.TQueryProfile;
import org.apache.doris.thrift.TRuntimeProfileTree;
import org.apache.doris.thrift.TStatusCode;
import org.apache.doris.thrift.TUniqueId;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * root is used to collect profile of a complete query plan(including query or load).
 * Need to call addToProfileAsChild() to add it to the root profile.
 * It has the following structure:
 *  DetailProfile:
 *      Fragment 0:
 *          Pipeline 0:
 *          ...
 *      Fragment 1:
 *          Pipeine 0:
 *          ...
 *      ...
 *      LoadChannels:  // only for load job
 */
public class ExecutionProfile {
    private static final Logger LOG = LogManager.getLogger(ExecutionProfile.class);

    private final TUniqueId queryId;
    private long queryFinishTime = 0L;
    // The root profile of this execution task
    private RuntimeProfile root;
    // Profiles for each fragment. And the InstanceProfile is the child of fragment profile.
    // Which will be added to fragment profile when calling Coordinator::sendFragment()
    // Could not use array list because fragment id is not continuous, planner may cut fragment
    // during planning.
    private Map<Integer, RuntimeProfile> fragmentProfiles;
    // Profile for load channels. Only for load job.
    private RuntimeProfile loadChannelProfile;

    // use to merge profile from multi be
    private Map<Integer, Map<TNetworkAddress, List<RuntimeProfile>>> multiBeProfile = null;
    private ReentrantReadWriteLock multiBeProfileLock = new ReentrantReadWriteLock();

    // Not serialize this property, it is only used to get profile id.
    private SummaryProfile summaryProfile;

    private Map<Integer, Integer> fragmentIdBeNum;
    private Map<Integer, Integer> seqNoToFragmentId;

    // Constructor does not need list<PlanFragment>, use List<FragmentId> is enough
    // and will be convenient for the test.
    public ExecutionProfile(TUniqueId queryId, List<Integer> fragmentIds) {
        this.queryId = queryId;
        root = new RuntimeProfile("DetailProfile(" + DebugUtil.printId(queryId) + ")");
        RuntimeProfile fragmentsProfile = new RuntimeProfile("Fragments");
        root.addChild(fragmentsProfile, true);
        fragmentProfiles = Maps.newHashMap();
        multiBeProfile = Maps.newHashMap();
        fragmentIdBeNum = Maps.newHashMap();
        seqNoToFragmentId = Maps.newHashMap();
        int i = 0;
        for (int fragmentId : fragmentIds) {
            RuntimeProfile runtimeProfile = new RuntimeProfile("Fragment " + i);
            fragmentProfiles.put(fragmentId, runtimeProfile);
            fragmentsProfile.addChild(runtimeProfile, true);
            multiBeProfile.put(fragmentId, Maps.newHashMap());
            fragmentIdBeNum.put(fragmentId, 0);
            seqNoToFragmentId.put(i, fragmentId);
            ++i;
        }
        loadChannelProfile = new RuntimeProfile("LoadChannels");
        root.addChild(loadChannelProfile, true);
    }

    private List<List<RuntimeProfile>> getMultiBeProfile(int fragmentId) {
        multiBeProfileLock.readLock().lock();
        try {
            // A fragment in the BE contains multiple pipelines, and each pipeline contains
            // multiple pipeline tasks.
            Map<TNetworkAddress, List<RuntimeProfile>> multiPipeline = multiBeProfile.get(fragmentId);
            List<List<RuntimeProfile>> allPipelines = Lists.newArrayList();
            int pipelineSize = -1;
            for (TNetworkAddress beAddress : multiPipeline.keySet()) {
                List<RuntimeProfile> profileSingleBE = multiPipeline.get(beAddress);
                // Check that within the same fragment across all BEs, there should be the same
                // number of pipelines.
                if (pipelineSize == -1) {
                    pipelineSize = profileSingleBE.size();
                } else {
                    if (pipelineSize != profileSingleBE.size()) {
                        LOG.warn("The profile sizes of the two BE are different, {} vs {}", pipelineSize,
                                profileSingleBE.size());
                        pipelineSize = Math.max(pipelineSize, profileSingleBE.size());
                    }
                }
            }
            for (int pipelineIdx = 0; pipelineIdx < pipelineSize; pipelineIdx++) {
                List<RuntimeProfile> allPipelineTask = new ArrayList<RuntimeProfile>();
                for (List<RuntimeProfile> profileSingleBE : multiPipeline.values()) {
                    RuntimeProfile pipeline = profileSingleBE.get(pipelineIdx);
                    for (Pair<RuntimeProfile, Boolean> pipelineTaskProfile : pipeline.getChildList()) {
                        allPipelineTask.add(pipelineTaskProfile.first);
                    }
                }
                if (allPipelineTask.isEmpty()) {
                    LOG.warn("None of the BEs have pipeline task profiles in fragmentId:{}  , pipelineIdx:{}",
                            fragmentId, pipelineIdx);
                }
                allPipelines.add(allPipelineTask);
            }
            return allPipelines;
        } finally {
            multiBeProfileLock.readLock().unlock();
        }
    }

    protected void setMultiBeProfile(int fragmentId, TNetworkAddress backendHBAddress,
                                List<RuntimeProfile> taskProfile) {
        multiBeProfileLock.writeLock().lock();
        try {
            multiBeProfile.get(fragmentId).put(backendHBAddress, taskProfile);
        } finally {
            multiBeProfileLock.writeLock().unlock();
        }
    }

    protected RuntimeProfile getPipelineAggregatedProfile(Map<Integer, String> planNodeMap) {
        RuntimeProfile fragmentsProfile = new RuntimeProfile("Fragments");
        for (int i = 0; i < fragmentProfiles.size(); ++i) {
            RuntimeProfile newFragmentProfile = new RuntimeProfile("Fragment " + i);
            fragmentsProfile.addChild(newFragmentProfile, true);
            // All pipeline profiles of this fragment on all BEs
            List<List<RuntimeProfile>> allPipelines = getMultiBeProfile(seqNoToFragmentId.get(i));
            int pipelineIdx = 0;
            for (List<RuntimeProfile> allPipelineTask : allPipelines) {
                RuntimeProfile mergedpipelineProfile = null;
                if (allPipelineTask.isEmpty()) {
                    // It is possible that the profile collection may be incomplete, so only part of
                    // the profile will be merged here.
                    mergedpipelineProfile = new RuntimeProfile(
                            "Pipeline " + pipelineIdx + "(miss profile)",
                            -pipelineIdx);
                } else {
                    mergedpipelineProfile = new RuntimeProfile(
                            "Pipeline " + pipelineIdx + "(instance_num="
                                    + allPipelineTask.size() + ")",
                            allPipelineTask.get(0).nodeId());
                    RuntimeProfile.mergeProfiles(allPipelineTask, mergedpipelineProfile, planNodeMap);
                }
                newFragmentProfile.addChild(mergedpipelineProfile, true);
                pipelineIdx++;
                fragmentsProfile.rowsProducedMap.putAll(mergedpipelineProfile.rowsProducedMap);
            }
        }
        return fragmentsProfile;
    }

    public RuntimeProfile getAggregatedFragmentsProfile(Map<Integer, String> planNodeMap) {
        for (RuntimeProfile fragmentProfile : fragmentProfiles.values()) {
            fragmentProfile.sortChildren();
        }
        /*
            * Fragment 0
            * ---Pipeline 0
            * ------pipelineTask 0
            * ------pipelineTask 0
            * ------pipelineTask 0
            * ---Pipeline 1
            * ------pipelineTask 1
            * ---Pipeline 2
            * ------pipelineTask 2
            * ------pipelineTask 2
            * Fragment 1
            * ---Pipeline 0
            * ------......
            * ---Pipeline 1
            * ------......
            * ---Pipeline 2
            * ------......
            * ......
            */
        return getPipelineAggregatedProfile(planNodeMap);
    }

    public RuntimeProfile getRoot() {
        return root;
    }

    public Status updateProfile(TQueryProfile profile, TNetworkAddress backendHBAddress, boolean isDone) {
        if (!profile.isSetQueryId()) {
            LOG.warn("QueryId is not set");
            return new Status(TStatusCode.INVALID_ARGUMENT, "QueryId is not set");
        }

        if (!profile.isSetFragmentIdToProfile()) {
            LOG.warn("{} FragmentIdToProfile is not set", DebugUtil.printId(profile.getQueryId()));
            return new Status(TStatusCode.INVALID_ARGUMENT, "FragmentIdToProfile is not set");
        }

        for (Entry<Integer, List<TDetailedReportParams>> entry : profile.getFragmentIdToProfile().entrySet()) {
            int fragmentId = entry.getKey();
            List<TDetailedReportParams> fragmentProfile = entry.getValue();
            int pipelineIdx = 0;
            List<RuntimeProfile> taskProfile = Lists.newArrayList();
            String suffix = "(host=" + backendHBAddress + ")";
            for (TDetailedReportParams pipelineProfile : fragmentProfile) {
                String name = "";
                boolean isFragmentLevel = (pipelineProfile.isSetIsFragmentLevel() && pipelineProfile.is_fragment_level);
                if (isFragmentLevel) {
                    // Fragment Level profile is also represented by TDetailedReportParams.
                    name = "FragmentLevelProfile:" + suffix;
                } else {
                    name = "Pipeline " + pipelineIdx + suffix;
                    pipelineIdx++;
                }

                RuntimeProfile profileNode = new RuntimeProfile(name);
                // The taskProfile is used to save the profile of the pipeline, without
                // considering the FragmentLevel.
                if (!isFragmentLevel) {
                    taskProfile.add(profileNode);
                }
                if (!pipelineProfile.isSetProfile()) {
                    LOG.warn("Profile is not set, {}", DebugUtil.printId(profile.getQueryId()));
                    return new Status(TStatusCode.INVALID_ARGUMENT, "Profile is not set");
                }

                profileNode.update(pipelineProfile.profile);
                profileNode.setIsDone(isDone);
                fragmentProfiles.get(fragmentId).addChild(profileNode, true);
            }
            setMultiBeProfile(fragmentId, backendHBAddress, taskProfile);
        }

        LOG.info("Profile update finished query: {} fragments: {} isDone: {}",
                DebugUtil.printId(getQueryId()), profile.getFragmentIdToProfile().size(), isDone);

        if (profile.isSetLoadChannelProfiles()) {
            for (TRuntimeProfileTree loadChannelProfile : profile.getLoadChannelProfiles()) {
                this.loadChannelProfile.update(loadChannelProfile);
            }
        }

        return new Status(TStatusCode.OK, "Success");
    }

    public synchronized void addFragmentBackend(PlanFragmentId fragmentId, Long backendId) {
        fragmentIdBeNum.put(fragmentId.asInt(), fragmentIdBeNum.get(fragmentId.asInt()) + 1);
    }

    public TUniqueId getQueryId() {
        return queryId;
    }

    // Check all fragments's child, if all finished, then this execution profile is finished
    public boolean isCompleted() {
        for (Entry<Integer, RuntimeProfile> element : fragmentProfiles.entrySet()) {
            RuntimeProfile fragmentProfile = element.getValue();
            // If any fragment is empty, it means BE does not report the profile, then the total
            // execution profile is not completed.
            if (fragmentProfile.isEmpty()
                    || fragmentProfile.getChildList().size() < fragmentIdBeNum.get(element.getKey())) {
                return false;
            }
            for (Pair<RuntimeProfile, Boolean> runtimeProfile : fragmentProfile.getChildList()) {
                // If any child instance profile is not ready, then return false.
                if (!(runtimeProfile.first.getIsDone() || runtimeProfile.first.getIsCancel())) {
                    return false;
                }
            }
        }
        return true;
    }

    public long getQueryFinishTime() {
        return queryFinishTime;
    }

    public void setQueryFinishTime(long queryFinishTime) {
        this.queryFinishTime = queryFinishTime;
    }

    public SummaryProfile getSummaryProfile() {
        return summaryProfile;
    }

    public void setSummaryProfile(SummaryProfile summaryProfile) {
        this.summaryProfile = summaryProfile;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        root.prettyPrint(sb, "");
        return sb.toString();
    }

    public void prettyPrint(StringBuilder sb, String prefix) {
        root.prettyPrint(sb, prefix);
    }
}
