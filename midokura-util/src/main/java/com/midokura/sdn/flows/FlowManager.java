/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.sdn.flows;

import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.sdn.dp.Flow;
import com.midokura.sdn.dp.FlowMatch;
import com.midokura.sdn.dp.flows.FlowAction;
import com.midokura.util.profiling.ProfilingTimer;

// not thread-safe
//TODO(ross) check the values mentioned in the doc below
/**
 * Hard Time-out
 * This class guarantees that every wildcard flow that has an hard time-out set
 * will be evicted after hard time-out + delta, where delta depends on how frequently the
 * Scheduler triggers the check for flow expiration and how long the operation takes.
 * We will test that and provide an estimation.
 * Idle Time-out
 * This class guarantees that every wildcard flow that has an idle time-out set
 * will be evicted after idle time-out + delta.
 * We have two priorities queues, one for hard time-out expiration and the other for
 * idle time-out expiration. They are ordered according to the time that each flow
 * has to live.
 * For idle time-out before deleting a wildcard flow, we get from the datapath
 * connection the lastUsedTime of each microflow until we find one whose
 * lastUsedTime < now - timeout. If that's the case, we extend the life of the
 * wildcard flow, otherwise we delete it.
 *
 * Idle Time-out expiration is an expensive operation. We won't accept
 * idle time-out < 5 s.
 */

//TODO(ross) create a priority queue of micro flows ordered according to the lastUsedTime
// we got from the kernel. When we have to free space we will delete the oldest one.
public class FlowManager {

    private static final Logger log = LoggerFactory.getLogger(FlowManager.class);

    private ProfilingTimer profiler = new ProfilingTimer(log);

    private FlowManagerHelper flowManagerHelper;
    private long maxDpFlows;
    //TODO(ross) is this a reasonable value? Take it from conf file?
    private int dpFlowRemoveBatchSize = 100;

    /** Since we want to be able to add flows in constant time, every time
     * a wcflow  matches and a new flow is created, instead of updating immediately
     * the wcflow LastUsedTime, we add it to this list. We will process the list later
     * when we check for flows expiration.
     */
    //private List<WildcardFlow> wildcardFlowsToUpdate = new ArrayList<WildcardFlow>();

    public FlowManager(FlowManagerHelper flowManagerHelper, long maxDpFlows) {
        this.maxDpFlows = maxDpFlows;
        this.flowManagerHelper = flowManagerHelper;
        if (dpFlowRemoveBatchSize > maxDpFlows)
            dpFlowRemoveBatchSize = 1;
    }

    public FlowManager(FlowManagerHelper flowManagerHelper, long maxDpFlows,
                       int dpFlowRemoveBatchSize) {
        this(flowManagerHelper, maxDpFlows);
        this.dpFlowRemoveBatchSize = dpFlowRemoveBatchSize;
    }

    /* Each wildcard flow table is a map of wildcard match to wildcard flow.
    * The FlowManager needs one wildcard flow table for every wildcard pattern,
    * where a pattern is a set of fields used by the match.
    * The wildcardTables structure maps wildcard pattern to wildcard flow
    * table.
    */
    private Map<Set<WildcardMatch.Field>, Map<WildcardMatch, WildcardFlow>>
        wildcardTables = new HashMap<Set<WildcardMatch.Field>,
        Map<WildcardMatch, WildcardFlow>>();

    /* The datapath flow table is a map of datapath FlowMatch to a list of
     * FlowActions. The datapath flow table is a LinkedHashMap so that we
     * can iterate it in insertion-order (e.g. to find flows that are
     * candidates for eviction).
     * This table reflect the flows installed in the kernel, gets updated only
     * by a callback of the DpConnection, that notifies the deletion of the
     * addiction of a flow.
     */
    private LinkedHashMap<FlowMatch, List<FlowAction<?>>> dpFlowTable =
        new LinkedHashMap<FlowMatch, List<FlowAction<?>>>();

    /* Map each datapath flow back to its wildcard flow */
    private Map<FlowMatch, WildcardFlow> dpFlowToWildFlow =
        new HashMap<FlowMatch, WildcardFlow>();
    /* Map each wildcard flow to the set of datapath flows it generated. */
    private Map<WildcardFlow, Set<FlowMatch>> wildFlowToDpFlows =
        new HashMap<WildcardFlow, Set<FlowMatch>>();
    //TODO(ross) size for the priority queue?
    final int priorityQueueSize = 10000;
    /* Priority queue to evict flows based on hard time-out */
    private PriorityQueue<WildcardFlow> hardTimeOutQueue =
        new PriorityQueue<WildcardFlow>(priorityQueueSize, new WildcardFlowHardTimeComparator());

    /* Priority queue to evict flows based on idle time-out */
    private PriorityQueue<WildcardFlow> idleTimeOutQueue =
        new PriorityQueue<WildcardFlow>(priorityQueueSize, new WildcardFlowIdleTimeComparator());
    /* Map to keep track of the kernel flows for which we required an update using
    FloManagerHelper.getFlow() that will in turn call dpConnection.getFlow
     */
    private HashMap<FlowMatch, Boolean> flowUpdateRequests = new HashMap<FlowMatch, Boolean>();

    public int getNumDpFlows() {
        return dpFlowTable.size();
    }

    /**
     * Add a new wildcard flow.
     *
     * @param wildFlow
     * @return True iff the wildcard flow was added. The flow will not be
     *         added if the table already contains a wildcard flow with the same
     *         match.
     */
    public boolean add(WildcardFlow wildFlow) {

        // Get the WildcardFlowTable for this wild flow's pattern.
        Set<WildcardMatch.Field> pattern =
            wildFlow.getMatch().getUsedFields();
        Map<WildcardMatch, WildcardFlow> wildTable = wildcardTables.get(pattern);
        if (null == wildTable) {
            wildTable = new HashMap<WildcardMatch, WildcardFlow>();
            wildcardTables.put(pattern, wildTable);
        }
        if (!wildTable.containsKey(wildFlow.wcmatch)) {
            wildTable.put(wildFlow.wcmatch, wildFlow);
            wildFlowToDpFlows.put(wildFlow, new HashSet<FlowMatch>());
            wildFlow.setCreationTimeMillis(System.currentTimeMillis());
            wildFlow.setLastUsedTimeMillis(System.currentTimeMillis());
            if(wildFlow.getHardExpirationMillis() > 0){
                hardTimeOutQueue.add(wildFlow);
                log.debug("Wildcard flow {} has hard time out set {}", wildFlow,
                          wildFlow.getHardExpirationMillis());
            }
            if(wildFlow.getIdleExpirationMillis() > 0){
                idleTimeOutQueue.add(wildFlow);
                log.debug("Wildcard flow {} has idle time out set {}", wildFlow,
                          wildFlow.getIdleExpirationMillis());
            }
            log.debug("Added wildcard flow {}", wildFlow.getMatch());
            return true;
        }
        return false;
    }

    /**
     * Add a new datapath flow and associate it to an existing wildcard flow
     * and its actions. The wildcard flow must have previously been successfully
     * added or the behavior is undefined.
     *
     * @param wildFlow
     * @param flowMatch
     * @return True iff both the datapath flow was added. The flow will not be
     *         added if the table already contains a datapath flow with the same
     *         match.
     */
    public boolean add(FlowMatch flowMatch, WildcardFlow wildFlow) {
        if (dpFlowTable.containsKey(flowMatch))
            return false;
        // check if there's enough space
        if(howManyFlowsToRemoveToFreeSpace() > 0){
            log.error("The flow table is full, cannot add flow with match {}",
                      flowMatch);
            // There's no point in trying to free space, because the remove operation
            // won't take please until the FlowController receive and process the
            // message RemoveFlow
        }
        dpFlowToWildFlow.put(flowMatch, wildFlow);
        wildFlowToDpFlows.get(wildFlow).add(flowMatch);

        log.debug("Added flow with match {} that matches wildcard flow {}",
                  flowMatch, wildFlow);
        return true;
    }

    /**
     * Remove a wildcard flow. Its associated datapath flows will be
     * removed when the notification of removal will be received.
     * If the wildcard flow has not been added, the method will return null.
     *
     * @param wildFlow
     * @return the set of datapath flows that was generated by this wild flow.
     */
    public void remove(WildcardFlow wildFlow) {
        log.debug("remove(WildcardFlow wildFlow) - Removing flow {}", wildFlow.getMatch());
        Set<FlowMatch> removedDpFlows = wildFlowToDpFlows.remove(wildFlow);
        if (removedDpFlows != null) {
            for (FlowMatch flowMatch : removedDpFlows) {
                dpFlowToWildFlow.remove(flowMatch);
                flowManagerHelper.removeFlow(new Flow().setMatch(flowMatch));
            }
            // Get the WildcardFlowTable for this wildflow's pattern and remove
            // the wild flow.
            Map<WildcardMatch, WildcardFlow> wcMap =
                wildcardTables.get(wildFlow.getMatch().getUsedFields());
            if (wcMap != null) {
                wcMap.remove(wildFlow.wcmatch);
                if (wcMap.size() == 0)
                    wildcardTables.remove(wildFlow.getMatch().getUsedFields());
            }
        }
    }

    /**
     * If a datapath flow matching this FlowMatch was already computed, return
     * its actions. Else null.
     *
     * @param flowMatch
     * @return
     */
    public List<FlowAction<?>> getActionsForDpFlow(FlowMatch flowMatch) {
        return dpFlowTable.get(flowMatch);
    }

    /**
     * If the datapath FlowMatch matches a wildcard flow, create the datapath
     * Flow. If the FlowMatch matches multiple wildcard flows, the FlowManager
     * will arbitrarily choose one that has the highest priority (lowest
     * priority value). If the FlowManager already contains a datapath flow
     * for this FlowMatch, it will return a copy of that instead.
     *
     * @param flowMatch
     * @return A datapath Flow if one already exists or a new one if the
     *         FlowMatch matches a wildcard flow. Otherwise, null.
     */
    public Flow createDpFlow(FlowMatch flowMatch) {
        // TODO(ross) why should we install a copy?
        List<FlowAction<?>> actions = dpFlowTable.get(flowMatch);
        if (null != actions)
            return new Flow().setMatch(flowMatch).setActions(actions);
        // Iterate through the WildcardFlowTables to find candidate wild flows.
        WildcardFlow wFlowCandidate = null;
        WildcardMatch flowWildMatch = WildcardMatches.fromFlowMatch(flowMatch);
        for (Map.Entry<Set<WildcardMatch.Field>,
            Map<WildcardMatch, WildcardFlow>> wTableEntry :
            wildcardTables.entrySet()) {
            Map<WildcardMatch, WildcardFlow> table = wTableEntry.getValue();
            Set<WildcardMatch.Field> pattern = wTableEntry.getKey();
            WildcardMatch projectedFlowMatch = WildcardMatches.project(
                pattern, flowWildMatch);
            WildcardFlow nextWFlowCandidate = table.get(projectedFlowMatch);
            if (null != nextWFlowCandidate) {
                if (null == wFlowCandidate)
                    wFlowCandidate = nextWFlowCandidate;
                else if (nextWFlowCandidate.priority < wFlowCandidate.priority)
                    wFlowCandidate = nextWFlowCandidate;
            }
        }
        // If we found a valid wildcard flow, create a Flow for the FlowMatch.
        if (null == wFlowCandidate){
            log.debug("FlowMatch {} didn't match any wildcard flow", flowMatch);
            return null;
        }
        else {
            Flow dpFlow = new Flow().setMatch(flowMatch)
                                    .setActions(wFlowCandidate.getActions());
            assert(add(flowMatch, wFlowCandidate));
            log.debug("FlowMatch {} matched wildcard flow {} ", flowMatch,
                      wFlowCandidate);
            return dpFlow;
        }
    }

    private void checkHardTimeOutExpiration(){
        profiler.start();
        WildcardFlow flowToExpire;
        while((flowToExpire = hardTimeOutQueue.peek()) != null){
            // since we remove the element lazily let's check if this el
            // has already been removed
            if(!wildFlowToDpFlows.containsKey(flowToExpire)){
                hardTimeOutQueue.poll();
                continue;
            }
            long timeLived = System.currentTimeMillis() - flowToExpire.getCreationTimeMillis();
            if( timeLived > flowToExpire.getHardExpirationMillis()){
                hardTimeOutQueue.poll();
                flowManagerHelper.removeWildcardFlow(flowToExpire);
                log.debug("Removing flow {} for hard expiration, expired {} ms ago", flowToExpire,
                          timeLived - flowToExpire.getHardExpirationMillis() );
            }else
                return;
        }
        profiler.end();
    }

    private void getKernelFlowsLastUsedTime(WildcardFlow flowToExpire){
        // check from the kernel the last time the flows of this wildcard flows
        // were used. This is totally asynchronous, a callback will update
        // the flows
        Set<FlowMatch> flowMatches = wildFlowToDpFlows.get(flowToExpire);
        for(FlowMatch match: flowMatches) {
            // only if we didn't request the update already
            if(!flowUpdateRequests.containsKey(match)){
                flowManagerHelper.getFlow(match);
                log.trace("Request update for kernel flows corresponding to " +
                              "wildcard flow {}", match);
            }
            flowUpdateRequests.put(match, true);
        }
    }

    private void checkIdleTimeExpiration(){
        profiler.start();
        WildcardFlow flowToExpire;
        Iterator it = idleTimeOutQueue.iterator();
        while(it.hasNext()){
            flowToExpire = (WildcardFlow)it.next();
            log.trace("Idle timeout queue size {}", idleTimeOutQueue.size());
            // since we remove the element lazily let's check if this element
            // has already been removed
            if(!wildFlowToDpFlows.containsKey(flowToExpire)){
                it.remove();
                continue;
            }
            long timeLived = System.currentTimeMillis() - flowToExpire.getLastUsedTimeMillis();
            if(timeLived > flowToExpire.getIdleExpirationMillis()/2){
                // these flows needs to be expired
                if( timeLived > flowToExpire.getIdleExpirationMillis()){
                    it.remove();
                    flowManagerHelper.removeWildcardFlow(flowToExpire);
                    log.debug("Removing flow {} for idle expiration, expired {} ms ago, now {}",
                              new Object[]{flowToExpire,
                              timeLived - flowToExpire.getIdleExpirationMillis(),
                              System.currentTimeMillis()});
                }
                // these flows are going to expire soon, we have to retrieve the
                // flow lastUsedTime from the kernel
                else{
                    getKernelFlowsLastUsedTime(flowToExpire);
                }
            }else
                break;
        }
        profiler.end();
    }

    private void manageDPFlowTableSpace() {
        removeOldestDpFlows((int) howManyFlowsToRemoveToFreeSpace());
    }

    private void removeOldestDpFlows(int nFlowsToRemove) {
        if (nFlowsToRemove <= 0)
            return;

        for (int i = 0; i < nFlowsToRemove; i++){
        Iterator<FlowMatch> it = dpFlowTable.keySet().iterator();
            if(it.hasNext()){
                FlowMatch match = it.next();
                // this call will eventually lead to the removal of this flow
                // from dpFlowTable
                flowManagerHelper.removeFlow(new Flow().setMatch(match));
                WildcardFlow wcFlow = dpFlowToWildFlow.remove(match);
                wildFlowToDpFlows.get(wcFlow).remove(match);
                if(wildFlowToDpFlows.get(wcFlow).size() == 0)
                    wildFlowToDpFlows.remove(wcFlow);
            }
        }
    }

    private long howManyFlowsToRemoveToFreeSpace() {
        return (getNumDpFlows() - (maxDpFlows - dpFlowRemoveBatchSize));
    }

    public void checkFlowsExpiration(){
        profiler.start();
        checkHardTimeOutExpiration();
        //updateWildcardLastUsedTime();
        checkIdleTimeExpiration();
        // check if there's enough space in the DP table
        manageDPFlowTableSpace();
        profiler.end();
    }

    public void updateFlowLastUsedTimeCompleted(Flow flow){
        //profiler.start();
        flowUpdateRequests.remove(flow.getMatch());
        WildcardFlow wcFlow = dpFlowToWildFlow.get(flow.getMatch());
        // the wildcard flow was deleted
        if(null == wcFlow)
            return;
        if (flow.getLastUsedTime() != null){
            if (flow.getLastUsedTime() > wcFlow.getLastUsedTimeMillis()) {
                log.trace("Wildcard flow {} lastUsedTime updated to {}", wcFlow, flow.getLastUsedTime());
                // TODO(ross) this is not optimal because we can may get a series of updates
                // of many kernel flows corresponding to this wc flow, we spend time
                // repositioning this wc in the queue but this could be done once
                // if we get all the flows updates together.
                wcFlow.setLastUsedTimeMillis(flow.getLastUsedTime());
            }
            //TODO(ross) is this cleanup necessary?
            /*
            else if (wcFlow.getHardExpirationMillis() > 0 && System.currentTimeMillis() - flow.getLastUsedTime()
                > wcFlow.getHardExpirationMillis() ||
                wcFlow.getIdleExpirationMillis() > 0 && System.currentTimeMillis() - flow.getLastUsedTime()
                    > wcFlow.getIdleExpirationMillis()) {
                //this is a good place to clean up old flows
                flowManagerHelper.removeFlow(flow);
                dpFlowToWildFlow.remove(flow.getMatch());
            } */
        }
        else{
            log.error("getFlow, flow with match {} was null or had no lastUsedTime set "
                , flow.getMatch());
        }
        //profiler.end();
    }

    public void addFlowCompleted(Flow flow){
        dpFlowTable.put(flow.getMatch(), flow.getActions());
        WildcardFlow wcFlow = dpFlowToWildFlow.get(flow.getMatch());
        if (wcFlow == null) {
            log.error("Could not find WildcardFlow for DP flow with match {} " +
                "in map {}", flow.getMatch(), dpFlowToWildFlow);
            return;
        }
        if(wcFlow.getIdleExpirationMillis() > 0){
            // TODO(pino): check with Rossella. Newly created flows will
            // TODO: always have a null lastUsedTime.
            if (null == flow.getLastUsedTime())
                wcFlow.setLastUsedTimeMillis(System.currentTimeMillis());
            else
                wcFlow.setLastUsedTimeMillis(flow.getLastUsedTime());
            log.trace("LastUsedTime updated for wildcard flow {}", wcFlow);
        }
    }

    public void removeFlowCompleted(Flow flow){
        dpFlowTable.remove(flow.getMatch());
    }

    private abstract class WildcardFlowComparator implements Comparator<WildcardFlow>{

        @Override
        public int compare(WildcardFlow wildcardFlow,
                           WildcardFlow wildcardFlow1) {
            long now = System.currentTimeMillis();
            long timeToLive1 = getTimeToLive(wildcardFlow, now);
            long timeToLive2 = getTimeToLive(wildcardFlow1, now);
            // they should both be deleted
            if(timeToLive1 < 0 && timeToLive2 < 0)
                return 0;
            return (int) (timeToLive1-timeToLive2);
        }

        protected abstract long getTimeToLive(WildcardFlow flow, long now);
    }

    private class WildcardFlowHardTimeComparator extends WildcardFlowComparator{
        @Override
        protected long getTimeToLive(WildcardFlow flow, long now) {
            return  now - flow.getCreationTimeMillis()
                - flow.getHardExpirationMillis();
        }
    }

    private class WildcardFlowIdleTimeComparator extends WildcardFlowComparator{
        @Override
        protected long getTimeToLive(WildcardFlow flow, long now) {
            return  now - flow.getLastUsedTimeMillis()
                - flow.getIdleExpirationMillis();
        }
    }

    Map<Set<WildcardMatch.Field>, Map<WildcardMatch, WildcardFlow>> getWildcardTables() {
        return wildcardTables;
    }

    LinkedHashMap<FlowMatch, List<FlowAction<?>>> getDpFlowTable() {
        return dpFlowTable;
    }

    Map<FlowMatch, WildcardFlow> getDpFlowToWildFlow() {
        return dpFlowToWildFlow;
    }

    Map<WildcardFlow, Set<FlowMatch>> getWildFlowToDpFlows() {
        return wildFlowToDpFlows;
    }
}
