/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */

package org.midonet.sdn.flows;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import akka.event.LoggingBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.flows.WildcardTablesProvider;
import org.midonet.odp.Flow;
import org.midonet.odp.FlowMatch;
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

    private Logger log = LoggerFactory.getLogger("org.midonet.flow-management");
    private static final int NO_LIMIT = 0;

    private FlowManagerHelper flowManagerHelper;
    private long maxDpFlows;
    private long maxWildcardFlows;
    //TODO(ross) is this a reasonable value? Take it from conf file?
    private int dpFlowRemoveBatchSize = 512;
    private int flowRequestsInFlight = 0;
    private long idleFlowToleranceInterval;

    public FlowManager(
            FlowManagerHelper flowManagerHelper,
            WildcardTablesProvider wildcardTables,
            long maxDpFlows, long maxWildcardFlows, long idleFlowToleranceInterval) {
        this.maxDpFlows = maxDpFlows;
        this.wildcardTables = wildcardTables;
        this.maxWildcardFlows = maxWildcardFlows;
        this.idleFlowToleranceInterval = idleFlowToleranceInterval;
        this.flowManagerHelper = flowManagerHelper;
        if (dpFlowRemoveBatchSize > maxDpFlows)
            dpFlowRemoveBatchSize = 1;
    }

    public FlowManager(
            FlowManagerHelper flowManagerHelper,
            WildcardTablesProvider wildcardTables,
            long maxDpFlows, long maxWildcardFlows, long idleFlowToleranceInterval,
            int dpFlowRemoveBatchSize) {
        this(flowManagerHelper, wildcardTables, maxDpFlows, maxWildcardFlows,
             idleFlowToleranceInterval);
        this.dpFlowRemoveBatchSize = dpFlowRemoveBatchSize;
    }

    /* Each wildcard flow table is a map of wildcard match to wildcard flow.
    * The FlowManager needs one wildcard flow table for every wildcard pattern,
    * where a pattern is a set of fields used by the match.
    * The wildcardTables structure maps wildcard pattern to wildcard flow
    * table.
    */
    private WildcardTablesProvider wildcardTables;

    /* The datapath flow table, a LinkedHashMap that allows iteration in
     * insertion-order, useful tofind flows that are candidates for eviction.
     * This table reflect the flows installed in the kernel.
     */
    public LinkedHashMap<FlowMatch, ManagedWildcardFlow> dpFlowTable =
        new LinkedHashMap<>((int)maxDpFlows);

    //TODO(ross) size for the priority queue?
    final int priorityQueueSize = 10000;
    /* Priority queue to evict flows based on hard time-out */
    private PriorityQueue<ManagedWildcardFlow> hardTimeOutQueue =
        new PriorityQueue<ManagedWildcardFlow>(priorityQueueSize, new WildcardFlowHardTimeComparator());

    /* Priority queue to evict flows based on idle time-out */
    private PriorityQueue<ManagedWildcardFlow> idleTimeOutQueue =
        new PriorityQueue<ManagedWildcardFlow>(priorityQueueSize, new WildcardFlowIdleTimeComparator());

    private int numWildcardFlows = 0;

    public int getNumDpFlows() {
        return dpFlowTable.size();
    }

    public int getNumWildcardFlows() {
        return numWildcardFlows;
    }

    public int evictOldestFlows() {
        int evicted = 0;
        for (evicted=0; evicted < dpFlowRemoveBatchSize; evicted++) {
            if (!evictOneFlow())
                break;
        }
        return evicted;
    }

    public boolean evictOneFlow() {
        ManagedWildcardFlow toEvict = !hardTimeOutQueue.isEmpty() ?
                hardTimeOutQueue.poll() : idleTimeOutQueue.poll();

        if (toEvict != null) {
            flowManagerHelper.removeWildcardFlow(toEvict);
            // timeout queue ref
            toEvict.unref();
            return true;
        } else {
            return false;
        }
    }

    /**
     * Add a new wildcard flow.
     *
     * @param wildFlow
     * @return True iff the wildcard flow was added. The flow will not be
     *         added if the table already contains a wildcard flow with the same
     *         match.
     */
    public boolean add(ManagedWildcardFlow wildFlow) {

        // check the wildcard flows limit
        if (maxWildcardFlows != NO_LIMIT && getNumWildcardFlows() >= maxWildcardFlows) {
            if (evictOldestFlows() == 0) {
                log.error("Could not add the new wildcardflow as the system reached its maximum.");
                return false;
            }
        }

        // WARNING: this is a ref to the private set itself
        Set<WildcardMatch.Field> pattern =
            wildFlow.getMatch().getUsedFields();

        // Get the WildcardFlowTable for this wild flow's pattern.
        Map<WildcardMatch, ManagedWildcardFlow> wildTable =
            wildcardTables.tables().get(pattern);
        if (null == wildTable) {
            // WARNING: use a copy of the field set because pattern is
            // a ref. to the private collection in the match
            wildTable = wildcardTables.addTable(EnumSet.copyOf(pattern));
        }
        if (!wildTable.containsKey(wildFlow.wcmatch())) {
            wildTable.put(wildFlow.wcmatch(), wildFlow);
            // FlowManager's ref
            wildFlow.ref();
            numWildcardFlows++;
            wildFlow.setCreationTimeMillis(System.currentTimeMillis());
            wildFlow.setLastUsedTimeMillis(System.currentTimeMillis());
            if (wildFlow.getHardExpirationMillis() > 0) {
                // timeout queue ref
                wildFlow.ref();
                hardTimeOutQueue.add(wildFlow);
            } else if (wildFlow.getIdleExpirationMillis() > 0){
                // timeout queue ref
                wildFlow.ref();
                idleTimeOutQueue.add(wildFlow);
            }
            return true;
        }
        log.warn("Can't add wildFlow, there is already a matching flow in " +
                 "the table: {}", wildTable.get(wildFlow.wcmatch()));
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
    public boolean add(Flow flow, ManagedWildcardFlow wildFlow) {
        FlowMatch match = flow.getMatch();
        if (log.isDebugEnabled() && dpFlowTable.containsKey(match)) {
            log.debug("Tried to add a duplicate DP flow");
        }

        wildFlow.dpFlows().add(match);
        dpFlowTable.put(match, wildFlow);

        log.debug("Added flow with match {} that matches wildcard flow {}",
                  flow.getMatch(), wildFlow.getMatch());

        if (wildFlow.getIdleExpirationMillis() > 0) {
            // TODO(pino): check with Rossella. Newly created flows will
            // TODO: always have a null lastUsedTime.
            if (null == flow.getLastUsedTime())
                wildFlow.setLastUsedTimeMillis(System.currentTimeMillis());
            else if (flow.getLastUsedTime() > wildFlow.getLastUsedTimeMillis())
                wildFlow.setLastUsedTimeMillis(flow.getLastUsedTime());
        }

        return true;
    }

    /**
     * Remove a wildcard flow and its associated datapath flows.
     *
     * @param wildFlow
     * @return true if the flow was alive, false otherwise
     */
    public boolean remove(ManagedWildcardFlow wildFlow) {
        log.debug("Removing wildcard flow {}", wildFlow.getMatch());

        Set<FlowMatch> dpFlowsToRemove = wildFlow.dpFlows();
        int removed = 0;
        for (FlowMatch flowMatch : dpFlowsToRemove) {
            /* the flow may have been evicted already, leaving for lazy
             * clean up of the wildcard flow reference */
            if (dpFlowTable.remove(flowMatch) != null) {
                flowManagerHelper.removeFlow(flowMatch);
                removed++;
            }
        }
        dpFlowsToRemove.clear();
        log.debug("Removed {} datapath flows", removed);

        // Get the WildcardFlowTable for this wildflow's pattern and remove
        // the wild flow.
        Map<WildcardMatch, ManagedWildcardFlow> wcMap =
            wildcardTables.tables().get(wildFlow.getMatch().getUsedFields());
        if (wcMap != null) {
            if (wcMap.get(wildFlow.wcmatch()) == wildFlow) {
                wcMap.remove(wildFlow.wcmatch());
                numWildcardFlows--;
                // FlowManager's ref
                wildFlow.unref();

                if (wcMap.isEmpty())
                    wildcardTables.tables().remove(wildFlow.getMatch().getUsedFields());

                return true;
            } else {
                log.debug("WildcardFlow missing from the WildcardFlowTable");
            }
        } else {
            log.debug("No WildcardFlowTable for the specified WildcardFlow pattern");
        }

        return false;
    }

    private boolean isAlive(ManagedWildcardFlow wildFlow) {
        Map<WildcardMatch, ManagedWildcardFlow> wcMap =
                wildcardTables.tables().get(wildFlow.getMatch().getUsedFields());
        if (wcMap != null)
            return wildFlow == wcMap.get(wildFlow.wcmatch());
        else
            return false;
    }

    private void checkHardTimeOutExpiration() {
        ManagedWildcardFlow flowToExpire;
        while ((flowToExpire = hardTimeOutQueue.peek()) != null) {
            // since we remove the element lazily let's check if this el
            // has already been removed
            if(!isAlive(flowToExpire)) {
                hardTimeOutQueue.poll();
                // timeout queue ref
                flowToExpire.unref();
                continue;
            }
            long timeLived = System.currentTimeMillis() - flowToExpire.getCreationTimeMillis();
            if (timeLived >= flowToExpire.getHardExpirationMillis()) {
                hardTimeOutQueue.poll();
                flowManagerHelper.removeWildcardFlow(flowToExpire);
                log.debug("Removing flow {} for hard expiration, expired {} ms ago",
                          flowToExpire.getMatch(),
                          timeLived - flowToExpire.getHardExpirationMillis());
                // timeout queue ref
                flowToExpire.unref();
            } else {
                return;
            }
        }
    }

    private void getKernelFlowsLastUsedTime(ManagedWildcardFlow flowToExpire) {
        // check from the kernel the last time the flows of this wildcard flows
        // were used. This is totally asynchronous, a callback will update
        // the flows
        Set<FlowMatch> flowMatches = flowToExpire.dpFlows();
        UpdateLastUsedTimeCallback callback =
            new UpdateLastUsedTimeCallback(flowToExpire, flowMatches.size());
        boolean dead = true;
        for (FlowMatch match: flowMatches) {
            if (dpFlowTable.containsKey(match)) {
                // getFlow callback ref
                flowToExpire.ref();
                flowManagerHelper.getFlow(match, callback);
                flowRequestsInFlight++;
                dead = false;
            } else {
                // clean lazily the deleted kernel flows
                flowMatches.remove(match);
                // adjust the updates to wait
                callback.nMissingFlowUpdates--;
            }
        }

        if (dead)
            flowManagerHelper.removeWildcardFlow(flowToExpire);
    }

    private void checkIdleTimeExpiration() {
        while (idleTimeOutQueue.peek() != null) {
            ManagedWildcardFlow flowToExpire = idleTimeOutQueue.peek();
            //log.trace("Idle timeout queue size {}", idleTimeOutQueue.size());
            // since we remove the element lazily let's check if this element
            // has already been removed
            if (!isAlive(flowToExpire)) {
                idleTimeOutQueue.poll();
                // timeout queue ref
                flowToExpire.unref();
                continue;
            }
            long expirationDate = flowToExpire.getLastUsedTimeMillis() +
                flowToExpire.getIdleExpirationMillis();
            // if the flow expired we don't delete it immediately, first we query
            // the kernel to get the updated lastUsedTime
            if (System.currentTimeMillis() >= expirationDate) {
                // remove it from the queue so we won't query it again
                idleTimeOutQueue.poll();
                getKernelFlowsLastUsedTime(flowToExpire);
                // timeout queue ref
                flowToExpire.unref();
            }else
                break;
        }

        if (flowRequestsInFlight > 0) {
            log.debug("Number of getFlow requests in flight {}",
                      flowRequestsInFlight);
        }
    }

    private void manageDPFlowTableSpace() {
        removeOldestDpFlows((int) howManyFlowsToRemoveToFreeSpace());
    }

    private void removeOldestDpFlows(int nFlowsToRemove) {
        if (nFlowsToRemove <= 0)
            return;

        Iterator<Map.Entry<FlowMatch, ManagedWildcardFlow>> it = dpFlowTable.entrySet().iterator();
        while (it.hasNext() && nFlowsToRemove-- > 0) {
            Map.Entry<FlowMatch, ManagedWildcardFlow> entry = it.next();
            FlowMatch match = entry.getKey();
            flowManagerHelper.removeFlow(match);
            entry.getValue().dpFlows().remove(match);
            it.remove();
        }
    }

    private long howManyFlowsToRemoveToFreeSpace() {
        return (getNumDpFlows() - (maxDpFlows - dpFlowRemoveBatchSize));
    }

    public void checkFlowsExpiration() {
        checkHardTimeOutExpiration();
        //updateWildcardLastUsedTime();
        checkIdleTimeExpiration();
        // check if there's enough space in the DP table
        manageDPFlowTableSpace();
    }

    // used in test to manipulate flows idle/hard expiration time values
    public ManagedWildcardFlow oldestIdleFlow() {
        return idleTimeOutQueue.peek();
    }

    public void flowMissing(FlowMatch flowMatch) {
        ManagedWildcardFlow wildcardFlow = dpFlowTable.remove(flowMatch);
        if (wildcardFlow != null) {
            wildcardFlow.dpFlows().remove(flowMatch);
            log.warn("DP flow was lost, forgetting: {}", flowMatch);
        }
    }

    private abstract class WildcardFlowComparator implements Comparator<ManagedWildcardFlow> {

        @Override
        public int compare(ManagedWildcardFlow wildcardFlow,
                           ManagedWildcardFlow wildcardFlow1) {
            long now = System.currentTimeMillis();
            long expirationTime1 = getExpirationTime(wildcardFlow, now);
            long expirationTime2 = getExpirationTime(wildcardFlow1, now);

            return (int) (expirationTime1-expirationTime2);
        }

        protected abstract long getExpirationTime(ManagedWildcardFlow flow, long now);
    }

    private class WildcardFlowHardTimeComparator extends WildcardFlowComparator {
        @Override
        protected long getExpirationTime(ManagedWildcardFlow flow, long now) {
            return flow.getCreationTimeMillis() + flow.getHardExpirationMillis();
        }
    }

    private class WildcardFlowIdleTimeComparator extends WildcardFlowComparator {
        @Override
        protected long getExpirationTime(ManagedWildcardFlow flow, long now) {
            return flow.getLastUsedTimeMillis() + flow.getIdleExpirationMillis();
        }
    }

    Map<Set<WildcardMatch.Field>, Map<WildcardMatch, ManagedWildcardFlow>> getWildcardTables() {
        return wildcardTables.tables();
    }

    ManagedWildcardFlow getWildcardFlow(WildcardMatch wMatch) {
        Map<WildcardMatch, ManagedWildcardFlow> wcMap =
                wildcardTables.tables().get(wMatch.getUsedFields());
        return (wcMap != null) ? wcMap.get(wMatch) : null;
    }

    /**
     * This callback is passed to flowsGet(). When flowsGet() returns with the
     * updated lastUsedTime we take a decision regarding the expiration of the
     * wcflow
     */
    class UpdateLastUsedTimeCallback implements Callback1<Flow> {

        ManagedWildcardFlow wcFlow;
        protected int nMissingFlowUpdates = 0;

        UpdateLastUsedTimeCallback(ManagedWildcardFlow wcFlow, int nFlowsToGet) {
            this.wcFlow = wcFlow;
            nMissingFlowUpdates = nFlowsToGet;
        }

        @Override
        public void call(Flow flowGotFromKernel) {
            // the wildcard flow was deleted

            if (!isAlive(wcFlow)) {
                // getFlow callback ref
                wcFlow.unref();
                return;
            }

            flowRequestsInFlight--;
            nMissingFlowUpdates--;

            if (flowGotFromKernel != null && flowGotFromKernel.getLastUsedTime() != null) {
                // update the lastUsedTime
                if (flowGotFromKernel.getLastUsedTime() > wcFlow.getLastUsedTimeMillis()) {
                    wcFlow.setLastUsedTimeMillis(flowGotFromKernel.getLastUsedTime());
                    log.trace("update lastUsedTime {}", flowGotFromKernel.getLastUsedTime());
                }
            }

            // is this the last kernel flow update that we are waiting?
            if (nMissingFlowUpdates == 0) {
                long expirationDate = wcFlow.getLastUsedTimeMillis() +
                    wcFlow.getIdleExpirationMillis();
                if (expirationDate - System.currentTimeMillis()
                    > idleFlowToleranceInterval) {
                    // add it back to the queue
                    // timeout queue ref
                    wcFlow.ref();
                    idleTimeOutQueue.add(wcFlow);

                } else {
                    // we can expire it
                    flowManagerHelper.removeWildcardFlow(wcFlow);
                    log.debug(
                        "Removing flow {} for idle expiration, expired {} ms ago",
                        wcFlow.getMatch(),
                        System.currentTimeMillis() - (wcFlow.getLastUsedTimeMillis()
                            + wcFlow.getIdleExpirationMillis()));
                }
            }

            // getFlow callback ref
            wcFlow.unref();
        }
    }
}
