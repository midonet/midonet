/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.sdn.flows;

import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.PriorityQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.odp.FlowMatch;

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
    private static int DEFAULT_FLOW_REMOVE_BATCH_SIZE = 512;

    private Logger log = LoggerFactory.getLogger("org.midonet.flow-management");

    private FlowManagerHelper flowManagerHelper;
    private int maxDpFlows;
    private int dpFlowRemoveBatchSize;
    private long idleFlowToleranceInterval;

    public FlowManager(FlowManagerHelper flowManagerHelper, int maxDpFlows,
                       long idleFlowToleranceInterval) {
        this(flowManagerHelper, maxDpFlows, idleFlowToleranceInterval,
             DEFAULT_FLOW_REMOVE_BATCH_SIZE);
    }

    public FlowManager(FlowManagerHelper flowManagerHelper, int maxDpFlows,
                       long idleFlowToleranceInterval, int dpFlowRemoveBatchSize) {
        this.maxDpFlows = maxDpFlows;
        this.idleFlowToleranceInterval = idleFlowToleranceInterval;
        this.flowManagerHelper = flowManagerHelper;
        if (dpFlowRemoveBatchSize > maxDpFlows)
            dpFlowRemoveBatchSize = 1;
        this.dpFlowRemoveBatchSize = dpFlowRemoveBatchSize;
    }

    public LinkedHashMap<FlowMatch, ManagedFlow> dpFlowTable = new LinkedHashMap<>((int)maxDpFlows);

    //TODO(ross) size for the priority queue?
    final int priorityQueueSize = 10000;
    /* Priority queue to evict flows based on hard time-out */
    private PriorityQueue<ManagedFlow> hardTimeOutQueue =
        new PriorityQueue<>(priorityQueueSize, new WildcardFlowHardTimeComparator());

    /* Priority queue to evict flows based on idle time-out */
    private PriorityQueue<ManagedFlow> idleTimeOutQueue =
        new PriorityQueue<>(priorityQueueSize, new WildcardFlowIdleTimeComparator());

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
    public boolean add(ManagedFlow wildFlow) {
        if (getNumDpFlows() > maxDpFlows)
            evictOldestFlows();

        FlowMatch fmatch = wildFlow.flowMatch();
        if (!dpFlowTable.containsKey(fmatch)) {
            dpFlowTable.put(fmatch, wildFlow);
            // FlowManager's ref
            wildFlow.ref();
            wildFlow.setCreationTimeMillis(System.currentTimeMillis());
            wildFlow.setLastUsedTimeMillis(System.currentTimeMillis());
            if (wildFlow.hardExpirationMillis() > 0) {
                // timeout queue ref
                wildFlow.ref();
                hardTimeOutQueue.add(wildFlow);
            } else if (wildFlow.idleExpirationMillis() > 0){
                // timeout queue ref
                wildFlow.ref();
                idleTimeOutQueue.add(wildFlow);
            }
            return true;
        }
        return false;
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
        ManagedFlow toEvict = hardTimeOutQueue.size() > 0
                            ? hardTimeOutQueue.poll()
                            : idleTimeOutQueue.poll();

        if (toEvict != null) {
            flowManagerHelper.removeWildcardFlow(toEvict);
            // timeout queue ref
            toEvict.unref();
            return true;
        }
        return false;
    }

    /**
     * Remove a wildcard flow and its associated datapath flows.
     *
     * @param wildFlow
     * @return true if the flow was alive, false otherwise
     */
    public boolean remove(ManagedFlow wildFlow) {
        log.debug("Removing managed flow {}", wildFlow);

        FlowMatch flowMatch = wildFlow.flowMatch();
        ManagedFlow removedFlow = dpFlowTable.remove(flowMatch);
        if (removedFlow == wildFlow) { // See isAlive()
            flowManagerHelper.removeFlow(wildFlow);
            wildFlow.unref(); // FlowManager's ref
            return true;
        } else if (removedFlow != null) {
            dpFlowTable.put(flowMatch, removedFlow);
        }
        return false;
    }

    private void checkHardTimeOutExpiration() {
        ManagedFlow flowToExpire;
        while ((flowToExpire = hardTimeOutQueue.peek()) != null) {
            // since we remove the element lazily let's check if this el
            // has already been removed
            if (!isAlive(flowToExpire)) {
                hardTimeOutQueue.poll();
                // timeout queue ref
                flowToExpire.unref();
                continue;
            }
            long timeLived = System.currentTimeMillis() - flowToExpire.getCreationTimeMillis();
            if (timeLived >= flowToExpire.hardExpirationMillis()) {
                hardTimeOutQueue.poll();
                flowManagerHelper.removeWildcardFlow(flowToExpire); // will remove remaining refs
                log.debug("Removing managed flow {} for hard expiration, expired {} ms ago",
                          flowToExpire,
                          timeLived - flowToExpire.hardExpirationMillis());
                // timeout queue ref
                flowToExpire.unref();
            } else {
                return;
            }
        }
    }

    // Check if the flow is still the same one we're trying to expire.
    // The flow specified by flowToExpire may have been removed while we issued
    // an asynchronous request and we may have added a new flow corresponding to
    // the same FlowMatch. That means flowToExpire is now stale.
    private boolean isAlive(ManagedFlow flowToExpire) {
        return dpFlowTable.get(flowToExpire.flowMatch()) == flowToExpire;
    }

    public void retrievedFlow(ManagedFlow managedFlow, long lastUsed) {
        if (!isAlive(managedFlow)) {
            return;
        }

        if (lastUsed > managedFlow.getLastUsedTimeMillis()) {
            managedFlow.setLastUsedTimeMillis(lastUsed);
            log.trace("update lastUsedTime {}", lastUsed);
        }

        long expirationDate = managedFlow.getLastUsedTimeMillis() + managedFlow.idleExpirationMillis();
        if (expirationDate - System.currentTimeMillis() > idleFlowToleranceInterval) {
            managedFlow.ref(); // timeout queue ref
            idleTimeOutQueue.add(managedFlow);
        } else {
            // we can expire it
            flowManagerHelper.removeWildcardFlow(managedFlow);
            log.debug(
                "Removing managed flow {} for idle expiration, expired {} ms ago",
                managedFlow,
                System.currentTimeMillis() - (managedFlow.getLastUsedTimeMillis()
                                           + managedFlow.idleExpirationMillis()));
        }
    }

    private void checkIdleTimeExpiration() {
        while (idleTimeOutQueue.peek() != null) {
            ManagedFlow flowToExpire = idleTimeOutQueue.peek();
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
                flowToExpire.idleExpirationMillis();
            // if the flow expired we don't delete it immediately, first we query
            // the kernel to get the updated lastUsedTime
            if (System.currentTimeMillis() >= expirationDate) {
                // remove it from the queue so we won't query it again
                idleTimeOutQueue.poll();
                flowManagerHelper.getFlow(flowToExpire);
                // timeout queue ref
                flowToExpire.unref();
            }else
                break;
        }
    }

    private void manageDPFlowTableSpace() {
        int excessFlows = getNumDpFlows() - (maxDpFlows - dpFlowRemoveBatchSize);
        if (excessFlows > 0)
            removeOldestDpFlows(excessFlows);
    }

    private void removeOldestDpFlows(int nFlowsToRemove) {
        Iterator<ManagedFlow> it = dpFlowTable.values().iterator();
        ManagedFlow[] toRemove = new ManagedFlow[nFlowsToRemove];
        for (int i = 0; i < nFlowsToRemove; ++i) {
            toRemove[i] = it.next();
        }
        for (int i = 0; i < nFlowsToRemove; ++i) {
            flowManagerHelper.removeWildcardFlow(toRemove[i]);
        }
    }

    public void checkFlowsExpiration() {
        checkHardTimeOutExpiration();
        checkIdleTimeExpiration();
        manageDPFlowTableSpace();
    }

    private abstract class WildcardFlowComparator implements Comparator<ManagedFlow> {

        @Override
        public int compare(ManagedFlow wildcardFlow,
                           ManagedFlow wildcardFlow1) {
            long now = System.currentTimeMillis();
            long expirationTime1 = getExpirationTime(wildcardFlow, now);
            long expirationTime2 = getExpirationTime(wildcardFlow1, now);

            return (int) (expirationTime1-expirationTime2);
        }

        protected abstract long getExpirationTime(ManagedFlow flow, long now);
    }

    private class WildcardFlowHardTimeComparator extends WildcardFlowComparator {
        @Override
        protected long getExpirationTime(ManagedFlow flow, long now) {
            return flow.getCreationTimeMillis() + flow.hardExpirationMillis();
        }
    }

    private class WildcardFlowIdleTimeComparator extends WildcardFlowComparator {
        @Override
        protected long getExpirationTime(ManagedFlow flow, long now) {
            return flow.getLastUsedTimeMillis() + flow.idleExpirationMillis();
        }
    }
}
