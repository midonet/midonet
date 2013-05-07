/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.sdn.flows;

import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import akka.event.LoggingAdapter;
import akka.event.LoggingBus;

import org.midonet.midolman.flows.WildcardTablesProvider;
import org.midonet.midolman.logging.LoggerFactory;
import org.midonet.odp.Flow;
import org.midonet.odp.FlowMatch;
import org.midonet.odp.flows.FlowAction;
import org.midonet.util.functors.Callback1;

// not thread-safe
//TODO(ross) check the values mentioned in the doc below
/**
 * Hard Time-out
 * This class guarantees that every wildcard flow that has an hard time-out set
 * will be evicted after hard time-out + delta, where delta depends on how
 * frequently the Scheduler triggers the check for flow expiration and how
 * long the operation takes.
 * We will test that and provide an estimate.
 *
 * Idle Time-out
 * This class guarantees that every wildcard flow that has an idle time-out set
 * will be evicted after idle time-out + delta.
 * We have two priority queues, one for hard time-out expiration and the other
 * for idle time-out expiration. They are ordered according to the time that
 * each flow has remaining.
 * For idle time-out before deleting a wildcard flow, we get from the datapath
 * connection the lastUsedTime of each microflow until we find one whose
 * lastUsedTime < now - timeout. If that's the case, we extend the life of the
 * wildcard flow, otherwise we delete it.
 *
 * Idle Time-out expiration is an expensive operation. We won't accept
 * idle time-out < 5 s.
 */

//TODO(ross) create a priority queue of micro flows ordered according to the
// lastUsedTime we got from the kernel. When we have to free space we will
// delete the oldest one.
public class FlowManager {

    private LoggingAdapter log;
    private static final int NO_LIMIT = 0;

    private FlowManagerHelper flowManagerHelper;
    private long maxDpFlows;
    private long maxWildcardFlows;
    //TODO(ross) is this a reasonable value? Take it from conf file?
    private int dpFlowRemoveBatchSize = 100;
    private int flowRequestsInFlight = 0;
    private long idleFlowToleranceInterval;

    /** Since we want to be able to add flows in constant time, every time
     * a wcflow  matches and a new flow is created, instead of updating immediately
     * the wcflow LastUsedTime, we add it to this list. We will process the list later
     * when we check for flows expiration.
     */
    //private List<WildcardFlow> wildcardFlowsToUpdate = new ArrayList<WildcardFlow>();

    public FlowManager(
            FlowManagerHelper flowManagerHelper,
            WildcardTablesProvider wildcardTables,
            long maxDpFlows, long maxWildcardFlows, long idleFlowToleranceInterval,
            LoggingBus loggingBus) {
        this.maxDpFlows = maxDpFlows;
        this.wildcardTables = wildcardTables;
        this.maxWildcardFlows = maxWildcardFlows;
        this.idleFlowToleranceInterval = idleFlowToleranceInterval;
        this.flowManagerHelper = flowManagerHelper;
        if (dpFlowRemoveBatchSize > maxDpFlows)
            dpFlowRemoveBatchSize = 1;
        this.log = LoggerFactory.getActorSystemThreadLog(
            this.getClass(), loggingBus);
    }

    public FlowManager(FlowManagerHelper flowManagerHelper,
            WildcardTablesProvider wildcardTables,
            long maxDpFlows, long maxWildcardFlows, long idleFlowToleranceInterval,
            LoggingBus loggingBus,
            int dpFlowRemoveBatchSize) {
        this(flowManagerHelper, wildcardTables, maxDpFlows, maxWildcardFlows,idleFlowToleranceInterval,
             loggingBus);
        this.dpFlowRemoveBatchSize = dpFlowRemoveBatchSize;
    }

    /* Each wildcard flow table is a map of wildcard match to wildcard flow.
    * The FlowManager needs one wildcard flow table for every wildcard pattern,
    * where a pattern is a set of fields used by the match.
    * The wildcardTables structure maps wildcard pattern to wildcard flow
    * table.
    */
    private WildcardTablesProvider wildcardTables;

    /* The datapath flow table is a map of datapath FlowMatch to a list of
     * FlowActions. The datapath flow table is a LinkedHashMap so that we
     * can iterate it in insertion-order (e.g. to find flows that are
     * candidates for eviction).
     * This table reflect the flows installed in the kernel, gets updated only
     * by a callback of the DpConnection, that notifies the deletion or the
     * addiction of a flow.
     */
    private LinkedHashMap<FlowMatch, FlowMetadata> dpFlowTable =
            new LinkedHashMap<FlowMatch, FlowMetadata>();

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

    private int numWildcardFlows = 0;

    public int getNumDpFlows() {
        return dpFlowTable.size();
    }

    public int getNumWildcardFlows() {
        return numWildcardFlows;
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

        // check the wildcard flows limit
        if (maxWildcardFlows != NO_LIMIT && getNumWildcardFlows() >= maxWildcardFlows) {
            // if there are too many try to do some cleanup.
            WildcardFlow toDelete = null;

            // need to delete a wildcarflow, first priority are the ones that have an idle timeout.
            if (idleTimeOutQueue.size() > 0) {
                toDelete = idleTimeOutQueue.poll();
            } else {
                // if there are none, delete one of the wildcarflows that contain a hard timeout.
                toDelete = hardTimeOutQueue.poll();
            }

            if (toDelete != null) {
                flowManagerHelper.removeWildcardFlow(toDelete);
            } else {
                log.error("Could not add the new wildcardflow as the system reached its maximum.");
                return false;
            }
        }

        // Get the WildcardFlowTable for this wild flow's pattern.
        Set<WildcardMatch.Field> pattern =
            wildFlow.getMatch().getUsedFields();
        Map<WildcardMatch, WildcardFlow> wildTable = wildcardTables.tables().get(pattern);
        if (null == wildTable)
            wildTable = wildcardTables.addTable(pattern);
        if (!wildTable.containsKey(wildFlow.wcmatch())) {
            wildTable.put(wildFlow.wcmatch(), wildFlow);
            numWildcardFlows++;
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
     * @param flow
     * @param wildFlow
     * @return True iff both the datapath flow was added. The flow will not be
     *         added if the table already contains a datapath flow with the same
     *         match.
     */
    public boolean add(Flow flow, WildcardFlow wildFlow) {
        if (dpFlowTable.containsKey(flow.getMatch())) {
            log.warning("Tried to add a duplicate DP flow");
            forgetFlow(flow.getMatch());
        }
        // check if there's enough space
        if (howManyFlowsToRemoveToFreeSpace() > 0) {
            log.info("The flow table is close to full capacity with {} dp flows",
                      getNumDpFlows());
            // There's no point in trying to free space, because the remove
            // operation won't take place until the FlowController receives
            // and processes the RemoveFlow message.
        }

        wildFlow.dpFlows().add(flow.getMatch());
        dpFlowTable.put(flow.getMatch(), new FlowMetadata(flow.getActions()));

        log.debug("Added flow with match {} that matches wildcard flow {}",
                  flow.getMatch(), wildFlow);

        if (wildFlow.getIdleExpirationMillis() > 0) {
            // TODO(pino): check with Rossella. Newly created flows will
            // TODO: always have a null lastUsedTime.
            if (null == flow.getLastUsedTime())
                wildFlow.setLastUsedTimeMillis(System.currentTimeMillis());
            else if (flow.getLastUsedTime() > wildFlow.getLastUsedTimeMillis())
                wildFlow.setLastUsedTimeMillis(flow.getLastUsedTime());
            //log.trace("LastUsedTime updated for wildcard flow {}", wcFlow);
        }

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
        Set<FlowMatch> removedDpFlows = wildFlow.dpFlows();
        if (removedDpFlows != null) {
            for (FlowMatch flowMatch : removedDpFlows) {
                flowManagerHelper.removeFlow(new Flow().setMatch(flowMatch));
            }
            // Get the WildcardFlowTable for this wildflow's pattern and remove
            // the wild flow.
            Map<WildcardMatch, WildcardFlow> wcMap =
                wildcardTables.tables().get(wildFlow.getMatch().getUsedFields());
            if (wcMap != null) {
                wcMap.remove(wildFlow.wcmatch());
                numWildcardFlows--;
                if (wcMap.size() == 0)
                    wildcardTables.tables().remove(wildFlow.getMatch().getUsedFields());
            }
            removedDpFlows.clear();
        }
    }

    private boolean isAlive(WildcardFlow wildFlow) {
        Map<WildcardMatch, WildcardFlow> wcMap =
                wildcardTables.tables().get(wildFlow.getMatch().getUsedFields());
        if (wcMap != null)
            return wildFlow == wcMap.get(wildFlow.wcmatch());
        else
            return false;
    }

    /**
     * If a datapath flow matching this FlowMatch was already computed, return
     * its actions. Else null.
     *
     * @param flowMatch
     * @return
     */
    public List<FlowAction<?>> getActionsForDpFlow(FlowMatch flowMatch) {
        FlowMetadata data = dpFlowTable.get(flowMatch);
        return (data != null) ? data.actions : null;
    }

    /**
     * If a datapath flow matching this FlowMatch was already computed, return
     * its time of creation.
     *
     * @param flowMatch
     * @return
     */
    public long getCreationTimeForDpFlow(FlowMatch flowMatch) {
        FlowMetadata data = dpFlowTable.get(flowMatch);
        return (data != null) ? data.creationTime : 0;
    }

    private void checkHardTimeOutExpiration(){
        WildcardFlow flowToExpire;
        while((flowToExpire = hardTimeOutQueue.peek()) != null){
            // since we remove the element lazily let's check if this el
            // has already been removed
            if(!isAlive(flowToExpire)){
                hardTimeOutQueue.poll();
                continue;
            }
            long timeLived = System.currentTimeMillis() - flowToExpire.getCreationTimeMillis();
            if( timeLived >= flowToExpire.getHardExpirationMillis()){
                hardTimeOutQueue.poll();
                flowManagerHelper.removeWildcardFlow(flowToExpire);
                log.debug("Removing flow {} for hard expiration, expired {} ms ago", flowToExpire,
                          timeLived - flowToExpire.getHardExpirationMillis() );
            }else
                return;
        }
    }

    private void getKernelFlowsLastUsedTime(WildcardFlow flowToExpire){
        // check from the kernel the last time the flows of this wildcard flows
        // were used. This is totally asynchronous, a callback will update
        // the flows
        Set<FlowMatch> flowMatches = flowToExpire.dpFlows();
        UpdateLastUsedTimeCallback callback =
            new UpdateLastUsedTimeCallback(flowToExpire, flowMatches.size());
        for(FlowMatch match: flowMatches) {
            if (dpFlowTable.containsKey(match)) {
                flowManagerHelper.getFlow(match, callback);
                flowRequestsInFlight++;
            } else {
                // clean lazily the deleted kernel flows
                flowMatches.remove(match);
                // adjust the updates to wait
                callback.nMissingFlowUpdates--;
            }
        }
    }

    private void checkIdleTimeExpiration(){
        WildcardFlow flowToExpire;
        Iterator it = idleTimeOutQueue.iterator();
        while(it.hasNext()){
            flowToExpire = (WildcardFlow)it.next();
            //log.trace("Idle timeout queue size {}", idleTimeOutQueue.size());
            // since we remove the element lazily let's check if this element
            // has already been removed
            if (!isAlive(flowToExpire)) {
                it.remove();
                continue;
            }
            long expirationDate = flowToExpire.getLastUsedTimeMillis() +
            flowToExpire.getIdleExpirationMillis();
            // if the flow expired we don't delete it immediately, first we query
            // the kernel to get the updated lastUsedTime
            if (System.currentTimeMillis() >= expirationDate) {
                    // remove it from the queue so we won't query it again
                    it.remove();
                    getKernelFlowsLastUsedTime(flowToExpire);
            }else
                break;
        }
        log.debug("Number of getFlow requests in flight {}", flowRequestsInFlight);
    }

    private void manageDPFlowTableSpace() {
        removeOldestDpFlows((int) howManyFlowsToRemoveToFreeSpace());
    }

    private void removeOldestDpFlows(int nFlowsToRemove) {
        if (nFlowsToRemove <= 0)
            return;

        Iterator<FlowMatch> it = dpFlowTable.keySet().iterator();
        int nFlowsRemoved = 0;
        while(it.hasNext() && nFlowsRemoved < nFlowsToRemove){
            // we will update the wildFlowToDpFlows lazily
            FlowMatch match = it.next();
            // this call will eventually lead to the removal of this flow
            // from dpFlowTable
            flowManagerHelper.removeFlow(new Flow().setMatch(match));
            nFlowsRemoved++;
        }
    }

    private long howManyFlowsToRemoveToFreeSpace() {
        return (getNumDpFlows() - (maxDpFlows - dpFlowRemoveBatchSize));
    }

    public void checkFlowsExpiration(){
        checkHardTimeOutExpiration();
        //updateWildcardLastUsedTime();
        checkIdleTimeExpiration();
        // check if there's enough space in the DP table
        manageDPFlowTableSpace();
    }

    public void forgetFlow(FlowMatch flowMatch) {
        dpFlowTable.remove(flowMatch);
    }

    public void removeFlowCompleted(Flow flow){
        dpFlowTable.remove(flow.getMatch());
    }

    private abstract class WildcardFlowComparator implements Comparator<WildcardFlow>{

        @Override
        public int compare(WildcardFlow wildcardFlow,
                           WildcardFlow wildcardFlow1) {
            long now = System.currentTimeMillis();
            long expirationTime1 = getExpirationTime(wildcardFlow, now);
            long expirationTime2 = getExpirationTime(wildcardFlow1, now);

            return (int) (expirationTime1-expirationTime2);
        }

        protected abstract long getExpirationTime(WildcardFlow flow, long now);
    }

    private class WildcardFlowHardTimeComparator extends WildcardFlowComparator{
        @Override
        protected long getExpirationTime(WildcardFlow flow, long now) {
            return flow.getCreationTimeMillis() + flow.getHardExpirationMillis();
        }
    }

    private class WildcardFlowIdleTimeComparator extends WildcardFlowComparator{
        @Override
        protected long getExpirationTime(WildcardFlow flow, long now) {
            return flow.getLastUsedTimeMillis() + flow.getIdleExpirationMillis();
        }
    }

    class FlowMetadata {
        public List<FlowAction<?>> actions;
        public final long creationTime;

        public FlowMetadata(List<FlowAction<?>> actions) {
            this.actions = actions;
            this.creationTime = System.currentTimeMillis();
        }
    }


    Map<Set<WildcardMatch.Field>, Map<WildcardMatch, WildcardFlow>> getWildcardTables() {
        return wildcardTables.tables();
    }

    LinkedHashMap<FlowMatch, FlowMetadata> getDpFlowTable() {
        return dpFlowTable;
    }

    WildcardFlow getWildcardFlow(WildcardMatch wMatch) {
        Map<WildcardMatch, WildcardFlow> wcMap =
                wildcardTables.tables().get(wMatch.getUsedFields());
        return (wcMap != null) ? wcMap.get(wMatch) : null;
    }

    /**
     * This callback is passed to flowsGet(). When flowsGet() returns with the
     * updated lastUsedTime we take a decision regarding the expiration of the
     * wcflow
     */
    class UpdateLastUsedTimeCallback implements Callback1<Flow> {

        WildcardFlow wcFlow;
        protected int nMissingFlowUpdates = 0;

        UpdateLastUsedTimeCallback(WildcardFlow wcFlow, int nFlowsToGet) {
            this.wcFlow = wcFlow;
            nMissingFlowUpdates = nFlowsToGet;
        }

        @Override
        public void call(Flow flowGotFromKernel) {
            // the wildcard flow was deleted
            if (!isAlive(wcFlow))
                return;

            flowRequestsInFlight--;
            nMissingFlowUpdates--;

            if (flowGotFromKernel != null && flowGotFromKernel.getLastUsedTime() != null) {
                // update the lastUsedTime
                if (flowGotFromKernel.getLastUsedTime() > wcFlow.getLastUsedTimeMillis()) {
                    wcFlow.setLastUsedTimeMillis(flowGotFromKernel.getLastUsedTime());
                    log.debug("update lastUsedTime {}", flowGotFromKernel.getLastUsedTime());
                }
            }

            // is this the last kernel flow update that we are waiting?
            if (nMissingFlowUpdates == 0) {
                long expirationDate = wcFlow.getLastUsedTimeMillis() +
                    wcFlow.getIdleExpirationMillis();
                if (expirationDate - System.currentTimeMillis()
                    > idleFlowToleranceInterval) {
                    // add it back to the queue
                    idleTimeOutQueue.add(wcFlow);

                } else {
                    // we can expire it
                    flowManagerHelper.removeWildcardFlow(wcFlow);
                    log.debug(
                        "Removing flow {} for idle expiration, expired {} ms ago",
                        wcFlow,
                        System.currentTimeMillis() - (wcFlow.getLastUsedTimeMillis()
                            + wcFlow.getIdleExpirationMillis()));
                }
            }

        }
    }
}
