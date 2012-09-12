/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.sdn.flows;

import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.netlink.Callback;
import com.midokura.netlink.exceptions.NetlinkException;
import com.midokura.sdn.dp.Flow;
import com.midokura.sdn.dp.FlowMatch;
import com.midokura.sdn.dp.flows.FlowAction;

// not thread-safe
// TODO(pino): document this
// TODO(pino): implement eviction of wildcard flows
// TODO(pino): implement flow removal notification. Keep in mind that during
// TODO:       simulation, the match for the installed flow is not yet known.
// TODO(ross) write a unit test for this class

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

    private FlowManagerHelper flowManagerHelper;
    private long maxDpFlows;
    //TODO(ross) is this a reasonable value? Take it from conf file?
    private int dpFlowRemoveBatchSize = 100;

    /** Since we want to be able to add flows in constant time, every time
     * a wcflow  matches and a new flow is created, instead of updating immediately
     * the wcflow lastMatchedTime, we add it to this list. We will process the list later
     * when we check for flows expiration.
     */
    private List<WildcardFlow> wildcardFlowsToUpdate = new ArrayList<WildcardFlow>();

    public FlowManager(FlowManagerHelper flowManagerHelper, long maxDpFlows) {
        this.maxDpFlows = maxDpFlows;
        this.flowManagerHelper = flowManagerHelper;
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
    
    public int getNumDpFlows() {
        return dpFlowTable.size();
    }

    public List<FlowMatch> removeOldestDpFlows(int numToRemove) {
        if (numToRemove <= 0)
            throw new IllegalArgumentException("numToRemove must be positive");
        List<FlowMatch> removed = new ArrayList<FlowMatch>(numToRemove);
        Iterator<FlowMatch> it = dpFlowTable.keySet().iterator();
        while (removed.size() < numToRemove && it.hasNext()) {
            removed.add(it.next());
            it.remove();
        }
        return removed;
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
        // check if there's enough space
        if(isFlowTableFull()){
            //TODO(ross) maybe stash the message if the table if full?
            log.error("The flow table is full, flow {} couldn't be added",
                      wildFlow.getMatch().toString());
            return false;
        }
        // Get the WildcardFlowTable for this wild flow's pattern.
        Set<WildcardMatch.Field> pattern =
                wildFlow.getMatch().getUsedFields();
        Map<WildcardMatch, WildcardFlow> wildTable = wildcardTables.get(pattern);
        if (null == wildTable) {
            wildTable = new HashMap<WildcardMatch, WildcardFlow>();
            wildcardTables.put(pattern, wildTable);
        }
        if (!wildTable.containsKey(wildFlow.match)) {
            wildTable.put(wildFlow.match, wildFlow);
            wildFlowToDpFlows.put(wildFlow, new HashSet<FlowMatch>());

            if(wildFlow.getHardExpirationMillis() > 0){
                hardTimeOutQueue.add(wildFlow);
                log.debug("Flow {} has hard time out set {}", wildFlow.toString(),
                          wildFlow.getHardExpirationMillis());
            }
            if(wildFlow.getIdleExpirationMillis() > 0){
                idleTimeOutQueue.add(wildFlow);
                log.debug("Flow {} has idle time out set {}", wildFlow.toString(),
                          wildFlow.getIdleExpirationMillis());
            }
            log.debug("Added flow {}", wildFlow.getMatch());
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
        dpFlowToWildFlow.put(flowMatch, wildFlow);
        wildFlowToDpFlows.get(wildFlow).add(flowMatch);
        // put the wcflow in the list to update later its lastMatchedTime
        wildFlow.setLastUsedTimeMillis(System.currentTimeMillis());
        wildcardFlowsToUpdate.add(wildFlow);
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
        log.debug("remove(WildcardFlow wildFlow) - Removing flow {}", wildFlow.getMatch().toString());
        Set<FlowMatch> removedDpFlows = wildFlowToDpFlows.remove(wildFlow);
        if (removedDpFlows != null){
            for (FlowMatch flowMatch : removedDpFlows) {
                dpFlowToWildFlow.remove(flowMatch);
                flowManagerHelper.removeFlow(new Flow().setMatch(flowMatch),
                                   getFlowDeleteCallback(flowMatch));
            }
            // Get the WildcardFlowTable for this wild flow's pattern and remove
            // the wild flow.
            Map<WildcardMatch, WildcardFlow> wcMap = wildcardTables.get(wildFlow.getMatch().getUsedFields());
            if(wcMap != null){
                wcMap.remove(wildFlow.match);
                if(wcMap.size() == 0)
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
        if (null == wFlowCandidate)
            return null;
        else {
            Flow dpFlow = new Flow().setMatch(flowMatch)
                    .setActions(wFlowCandidate.getActions());
            assert(add(flowMatch, wFlowCandidate));
            return dpFlow;
        }
    }

    private void checkHardTimeOutExpiration(){
        WildcardFlow flowToExpire;
        while((flowToExpire = hardTimeOutQueue.peek()) != null){
            long timeLived = System.currentTimeMillis() - flowToExpire.getCreationTimeMillis();
            if( timeLived > flowToExpire.getHardExpirationMillis()){
                hardTimeOutQueue.poll();
                // since we remove the element lazily let's check if this el
                // has already been removed
                if(wildFlowToDpFlows.containsKey(flowToExpire)){
                    flowManagerHelper.removeWildcardFlow(flowToExpire);
                }
                log.debug("Removing flow {} for hard expiration, expired {} ms ago", flowToExpire.toString(),
                          timeLived - flowToExpire.getHardExpirationMillis() );
            }
            else
                return;
        }
    }
    
    private boolean checkMicroFlowsExpiration(WildcardFlow flowToExpire){
        // check from the kernel the last time the flows of this wildcardflows
        // were used
        Set<FlowMatch> flowMatches = wildFlowToDpFlows.get(flowToExpire);
        Iterator<FlowMatch> matchIterator = flowMatches.iterator();
         while (matchIterator.hasNext()) {
             FlowMatch match = matchIterator.next();
             Flow kernelFlow = flowManagerHelper.getFlow(match);
             if( kernelFlow != null && kernelFlow.getLastUsedTime() != null){
                 if (System.currentTimeMillis() - kernelFlow.getLastUsedTime()
                     < flowToExpire.getIdleExpirationMillis()) {
                     // no need to check other flows, let's update the wildcardflow
                     flowToExpire.setLastUsedTimeMillis(kernelFlow.getLastUsedTime());
                     log.debug("flow {} lastUsedTime update to {}", flowToExpire, kernelFlow.getLastUsedTime());
                     idleTimeOutQueue.add(flowToExpire);
                     return true;
                 } else {
                     //delete the flow if it's expired
                     flowManagerHelper.removeFlow(kernelFlow, getFlowDeleteCallback(match));
                     dpFlowToWildFlow.remove(match);
                     matchIterator.remove();
                 }
             }
             else{
                 log.debug("getFlow, flow {} was null or had no lastUsedTime set "
                     , match.toString());
             }

        }
        return false;
    }
    
    private void checkIdleTimeExpiration(){
        WildcardFlow flowToExpire;
        while((flowToExpire = idleTimeOutQueue.peek()) != null){
            long timeLived = System.currentTimeMillis() - flowToExpire.getLastUsedTimeMillis();
            if( timeLived > flowToExpire.getIdleExpirationMillis()){
                idleTimeOutQueue.poll();
                // since we remove the element lazily let's check if this element
                // has already been removed
                if(!wildFlowToDpFlows.containsKey(flowToExpire)){
                    continue;
                }
                boolean flowsAlive = checkMicroFlowsExpiration(flowToExpire);
                if(!flowsAlive){
                    flowManagerHelper.removeWildcardFlow(flowToExpire);
                    log.debug("Removing flow {} for idle expiration, expired {} ms ago", flowToExpire.toString(),
                              timeLived - flowToExpire.getHardExpirationMillis() );
                }
            }
            else
                return;
        }
    }
    
    private void updateWildcardFlowLastMatchedTime(){
        for(WildcardFlow flow: wildcardFlowsToUpdate){
            // we can remove the old flow using the update one because in the
            // equal method we don't take into consideration the field lastMatchedTime
            if(idleTimeOutQueue.remove(flow)){
                // this flow will contain the updated lastMatchedTime
                idleTimeOutQueue.add(flow);
            }
        }
        wildcardFlowsToUpdate.clear();
    }

    private void manageDPFlowTableSpace() {
        if (isFlowTableFull()) {
            int size = dpFlowTable.keySet().size();
            // we create an array because if the flowManagerHelper.removeFlow
            // operation is executed immediately we'd get a ConcurrentModificationEx
            // because we'd iterate in dpFlowTable.keySet and delete and element
            // of the set in the callback triggered by flowManagerHelper.removeFlow
            FlowMatch[] matches = dpFlowTable.keySet().toArray(new FlowMatch[size]);
            int removedFlowsCount = 0;
            while (removedFlowsCount < dpFlowRemoveBatchSize && removedFlowsCount < size) {
                flowManagerHelper.removeFlow(new Flow().setMatch(matches[removedFlowsCount]),
                                             getFlowDeleteCallback(matches[removedFlowsCount]));
                WildcardFlow wcFlow = dpFlowToWildFlow.remove(matches[removedFlowsCount]);
                wildFlowToDpFlows.get(wcFlow).remove(matches[removedFlowsCount]);
                if(wildFlowToDpFlows.get(wcFlow).size() == 0)
                    wildFlowToDpFlows.remove(wcFlow);
                removedFlowsCount++;
            }
        }
    }

    private boolean isFlowTableFull() {
        return (getNumDpFlows() > maxDpFlows - dpFlowRemoveBatchSize);
    }
    
    public void checkFlowsExpiration(){

        checkHardTimeOutExpiration();
        updateWildcardFlowLastMatchedTime();
        checkIdleTimeExpiration();
        // check if there's enough space in the DP table
        manageDPFlowTableSpace();
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
    
    private abstract class FlowManagerCallback implements Callback<Flow> {
        Flow flowToRemove;

        protected FlowManagerCallback(Flow flowToRemove) {
            this.flowToRemove = flowToRemove;
        }

        @Override
        public void onTimeout() {
            String match = flowToRemove.getMatch() == null ? "" :
                flowToRemove.getMatch().toString();
            log.error("Failed to" + getActionName() + "flow {} due to time out",
                      match);
        }

        @Override
        public void onError(NetlinkException e) {
            String match = flowToRemove.getMatch() == null ? "" :
                flowToRemove.getMatch().toString();
            log.error("Failed to" + getActionName() + "flow {} due to", match, e);
        }
        abstract String getActionName();
    }
    
    private Callback<Flow> getFlowDeleteCallback(final FlowMatch match){
        return new FlowManagerCallback(new Flow().setMatch(match)) {

            @Override
            public void onSuccess(Flow data) {
                // now we can remove the flow
                dpFlowTable.remove(data.getMatch());
            }

            @Override
            String getActionName() {
                return "remove";
            }
        };
    }

    
    public Callback<Flow> getFlowCreatedCallback(final Flow flowToAdd){
        return new FlowManagerCallback(flowToAdd) {
            @Override
            public void onSuccess(Flow data) {
                // add the flow to the table
                dpFlowTable.put(data.getMatch(), data.getActions());
            }

            @Override
            String getActionName() {
                return "add";
            }
        };
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
